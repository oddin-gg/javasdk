package com.oddin.oddsfeedsdk.internal.events;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.ConnectionState;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.ProducerCauseChange;
import com.oddin.oddsfeedsdk.subscribe.SafetyNetEvent;
import com.oddin.oddsfeedsdk.subscribe.SessionLagChange;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The events dispatcher: one thread that runs every callback of the feed as a whole on the client's
 * {@link GlobalEventsListener}, and the raw API data on its {@link OddsFeedExtListener}. Whoever
 * reports an event - the transport, the recovery actor, a REST worker, a session's dispatcher - only
 * puts it in a queue and returns, never waiting for the client and never running its code.
 *
 * <p>Two queues. The control queue carries what the client's view of the feed is made of: the
 * connection's state, producer status and its cause, fatal errors, event recovery completions, the
 * safety net's resets and the sessions lagging. It holds {@value #CONTROL_CAPACITY}; an event with
 * no room is counted and logged, never waited for. Of those, each producer's status, each
 * producer's cause, each session's lagging, the connection's state and each kind of fatal error have
 * one slot, which the newest fills: such an event replaces one still queued rather than queueing behind it, and is
 * delivered where it was reported, after what was reported before it. So none of them is ever
 * dropped, a client that falls behind hears the state as it is now rather than one long gone, in the
 * order it changed - the last word of a producer is never the one that found no room, nor heard
 * before a connection loss reported ahead of it - and a flood of them takes one queued entry each. A
 * connection loss replaced that way is still told, by {@code onConnectionDown}. The telemetry
 * queue carries the API calls, the failed callbacks and the raw API data; it holds {@value
 * #TELEMETRY_CAPACITY} and drops the oldest when full, counting them. The raw API data it holds,
 * and the response being delivered until both its callbacks are done, are bounded by bytes too, at
 * {@link #TELEMETRY_BYTES}: a response with no room under that is dropped and counted, since a
 * thousand of the largest the API may answer would exhaust the heap while a callback lags.
 *
 * <p>The thread takes the control queue first, then up to {@value #TELEMETRY_PER_TURN} telemetry
 * events, each only while no control event waits. A callback that throws, even an error, is
 * logged, counted and reported to {@code onCallbackFailure}, and the next event is delivered as
 * usual; a thread interrupt a callback leaves behind is cleared.
 *
 * <p>Safe for concurrent use.
 */
public final class EventsDispatcher implements ApiEvents, ConnectionEvents, RecoveryEvents, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventsDispatcher.class);

    static final int CONTROL_CAPACITY = 10_000;
    static final int TELEMETRY_CAPACITY = 1_000;
    /**
     * The most bytes of raw API responses the telemetry queue holds: the largest response the
     * decoder takes, so one of those always fits when nothing else waits, while entity responses of
     * a few kilobytes fill the queue's thousand entries long before. The decoded object each one
     * holds as well is a few times its size, so the whole stays around a hundred megabytes at most.
     */
    static final long TELEMETRY_BYTES = RestDecoder.DEFAULT_MAX_BYTES;
    /** How many telemetry events the thread delivers before it looks at the control queue again. */
    private static final int TELEMETRY_PER_TURN = 100;
    /** How long the thread waits for an event before it looks again, should a wake-up be missed. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long close() waits for the thread to end; a feed closing gives every dispatcher one deadline instead. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private static final String ON_CALLBACK_FAILURE = "onCallbackFailure";

    private final GlobalEventsListener listener;
    private final @Nullable OddsFeedExtListener extListener;
    private final LongFunction<@Nullable Producer> producers;
    private final IntFunction<@Nullable OddsFeedSession> sessions;
    private final InstantSource clock;
    private final int controlCapacity;

    /** The control events and the slots' markers, in the order they came. */
    private final Queue<Entry> control = new ConcurrentLinkedQueue<>();
    /** How many control events are queued, not counting the slots' markers. */
    private final AtomicInteger controlSize = new AtomicInteger();
    /**
     * What each slot holds that is not delivered yet; a slot with something in it has one marker
     * queued, where its newest report stands.
     */
    private final Map<Object, Held> slots = new ConcurrentHashMap<>();
    /**
     * Held while a report fills its slot and moves the slot's marker, so a report never takes out
     * the marker it replaces before that marker is queued, which would leave it queued for good.
     */
    private final ReentrantLock filling = new ReentrantLock();
    /** The generation of each report a slot takes, increasing; guarded by {@link #filling}. */
    private long generation;
    /** A test's hook: runs in a report once its slot holds it, before its marker is queued. */
    volatile Runnable afterFill = () -> {};
    /** A test's hook: runs on the events thread once it has read a marker's slot, before it empties it. */
    volatile Runnable afterRead = () -> {};

    private final Queue<Event> telemetry;

    private final AtomicLong controlDropped = new AtomicLong();
    private final AtomicLong telemetryDropped = new AtomicLong();
    /** The bytes of raw API responses the telemetry queue holds now. */
    private final AtomicLong telemetryBytes = new AtomicLong();

    private final long telemetryByteBudget;
    private final AtomicLong rawDataDropped = new AtomicLong();
    private final AtomicLong callbackFailures = new AtomicLong();

    private final Thread thread;
    private volatile boolean closed;
    /** When the callback running now started, epoch millis, 0 between callbacks; for the watchdog. */
    private volatile long busySince;

    /**
     * With no sessions to name: the safety net's events and the sessions lagging are not delivered.
     *
     * @param extListener the client's extended listener, null for none
     * @param producers what a producer status message names its producer from, by id: asked only
     *     when a status is delivered, so the feed binds it once the producer list has come, through a
     *     REST client that already reports here
     */
    public EventsDispatcher(
            GlobalEventsListener listener,
            @Nullable OddsFeedExtListener extListener,
            LongFunction<@Nullable Producer> producers) {
        this(listener, extListener, producers, session -> null);
    }

    /**
     * @param extListener the client's extended listener, null for none
     * @param producers what a producer status message names its producer from, by id: asked only
     *     when a status is delivered, so the feed binds it once the producer list has come, through a
     *     REST client that already reports here
     * @param sessions what the safety net's events and the sessions lagging name their session from,
     *     by the id the recovery actor knows it by: asked only when one is delivered, so the feed
     *     binds it once the sessions are built; one it does not name is not delivered
     */
    public EventsDispatcher(
            GlobalEventsListener listener,
            @Nullable OddsFeedExtListener extListener,
            LongFunction<@Nullable Producer> producers,
            IntFunction<@Nullable OddsFeedSession> sessions) {
        this(
                listener,
                extListener,
                producers,
                sessions,
                InstantSource.system(),
                CONTROL_CAPACITY,
                TELEMETRY_CAPACITY,
                TELEMETRY_BYTES);
    }

    /** With the clock and the queue sizes a test sets. */
    EventsDispatcher(
            GlobalEventsListener listener,
            @Nullable OddsFeedExtListener extListener,
            LongFunction<@Nullable Producer> producers,
            IntFunction<@Nullable OddsFeedSession> sessions,
            InstantSource clock,
            int controlCapacity,
            int telemetryCapacity,
            long telemetryByteBudget) {
        this.listener = listener;
        this.extListener = extListener;
        this.producers = producers;
        this.sessions = sessions;
        this.clock = clock;
        this.controlCapacity = controlCapacity;
        this.telemetry = new ArrayBlockingQueue<>(telemetryCapacity);
        this.telemetryByteBudget = telemetryByteBudget;
        this.thread = Thread.ofPlatform().daemon().name("oddsfeed-events").unstarted(this::run);
    }

    /** Starts delivering; events reported before are queued until then. */
    public void start() {
        thread.start();
    }

    // ------------------------------------------------------------------ the recovery actor

    @Override
    public void producerStatus(ProducerStatusChange change) {
        coalesce(
                new ProducerSlot(change.producerId()),
                new Latest(event("onProducerStatusChange", () -> listener.onProducerStatusChange(message(change)))));
    }

    @Override
    public void producerCause(ProducerStatusChange change) {
        coalesce(
                new CauseSlot(change.producerId()),
                new Latest(event("onProducerCauseChange", () -> listener.onProducerCauseChange(causeChange(change)))));
    }

    @Override
    public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
        control(event("onEventRecoveryCompleted", () -> listener.onEventRecoveryCompleted(eventId, requestId)));
    }

    @Override
    public void safetyNetReset(int session, long producerId, long ageMillis) {
        safetyNet(session, SafetyNetEvent.Kind.RESET, producerId, Duration.ofMillis(ageMillis), null);
    }

    @Override
    public void safetyNetRequestFailed(int session, long producerId, String reason) {
        safetyNet(session, SafetyNetEvent.Kind.REQUEST_FAILED, producerId, Duration.ZERO, reason);
    }

    @Override
    public void lagging(int session, boolean lagging) {
        Instant at = clock.instant();
        coalesce(
                new LagSlot(session),
                new Latest(event(
                        "onSessionLagChange",
                        () -> named(
                                session,
                                named -> listener.onSessionLagChange(new SessionLagChange(named, lagging, at))))));
    }

    // ------------------------------------------------------------------ the transport

    @Override
    public void connecting() {
        connection(ConnectionState.CONNECTING, null, 0, 0);
    }

    @Override
    public void up() {
        connection(ConnectionState.UP, null, 0, 0);
    }

    @Override
    public void down(String reason) {
        connection(ConnectionState.DOWN, reason, 0, 0);
    }

    @Override
    public void recovering(int attempt, long waitMillis, String reason) {
        connection(ConnectionState.RECOVERING, reason, attempt, waitMillis);
    }

    @Override
    public void fatal(String reason, @Nullable Throwable cause) {
        coalesce(Slot.BROKER_FATAL, new Latest(event("onFatalError", () -> listener.onFatalError(reason, cause))));
    }

    // ------------------------------------------------------------------ the REST client

    @Override
    public void called(ApiCall call) {
        var event = new ApiCallEvent(
                call.method(),
                call.uri(),
                call.status(),
                call.latency(),
                call.attempt(),
                call.failure(),
                clock.instant());
        telemetry(event("onApiCall", () -> listener.onApiCall(event)));
    }

    @Override
    public void refused(ApiCall call) {
        String reason =
                "The API refused the access token: " + call.method() + " " + call.uri() + " answered " + call.status();
        Exception cause = call.failure();
        coalesce(Slot.API_FATAL, new Latest(event("onFatalError", () -> listener.onFatalError(reason, cause))));
    }

    @Override
    public void received(URI uri, Object decoded, byte[] body) {
        OddsFeedExtListener ext = extListener;
        if (ext == null) {
            return;
        }
        if (closed || !reserve(body.length)) {
            return;
        }
        telemetry(new Event(
                List.of(
                        new Call("onRawApiDataReceived", () -> ext.onRawApiDataReceived(uri, decoded)),
                        new Call("onRawApiDataBytes", () -> ext.onRawApiDataBytes(uri, body))),
                body.length));
    }

    // ------------------------------------------------------------------ the sessions

    /**
     * A session's callback threw, or the SDK failed on one of its messages: reported to {@code
     * onCallbackFailure}. The caller has logged it.
     *
     * @param session the session, null for a callback of the feed as a whole
     */
    public void callbackFailed(
            String callback, boolean clientCode, Throwable exception, @Nullable OddsFeedSession session) {
        var failure = new CallbackFailure(callback, clientCode, exception, session, clock.instant());
        telemetry(event(ON_CALLBACK_FAILURE, () -> listener.onCallbackFailure(failure)));
    }

    // ------------------------------------------------------------------ for the watchdog and getHealth()

    /** When the callback running now started, epoch millis by the dispatcher's clock; 0 when none runs. */
    public long busySince() {
        return busySince;
    }

    /** Control events dropped for want of room. */
    public long controlDropped() {
        return controlDropped.get();
    }

    /** Raw API responses dropped because the telemetry queue held its budget of bytes already. */
    public long rawDataDropped() {
        return rawDataDropped.get();
    }

    /** Telemetry events dropped, the oldest first, for want of room. */
    public long telemetryDropped() {
        return telemetryDropped.get();
    }

    /** Entries in the control queue, the slots' markers included; for a test. */
    int controlQueued() {
        return control.size();
    }

    /** Callbacks that threw. */
    public long callbackFailures() {
        return callbackFailures.get();
    }

    /** Stops delivering. Events still queued are dropped, and so is anything reported from now on. */
    @Override
    public void close() {
        stop();
        awaitStop(System.nanoTime() + CLOSE_WAIT.toNanos());
    }

    /**
     * Tells the thread to stop once the callback it runs returns, and returns at once: nothing more
     * is delivered, and nothing reported from now on is queued. So a feed closing tells this and
     * every other dispatcher before it waits for any, and waits for them all within one deadline.
     */
    public void stop() {
        closed = true;
        LockSupport.unpark(thread);
    }

    /**
     * Waits for the thread to end after a {@link #stop}, until {@code deadline}, by {@link
     * System#nanoTime}, then drops the events still queued; says so in the log when it does not end.
     * From the events thread itself - a callback closing the feed - it does not wait, as that would
     * wait for itself: the thread ends once the callback returns.
     *
     * @return whether the thread has ended, or is the caller's own and ends next
     */
    public boolean awaitStop(long deadline) {
        try {
            if (Thread.currentThread().equals(thread) || !thread.isAlive()) {
                return true;
            }
            if (thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clearQueues();
        }
        if (!thread.isAlive()) {
            return true;
        }
        LOG.warn("The events dispatcher did not stop in time: a callback still runs");
        return false;
    }

    private void clearQueues() {
        control.clear();
        slots.clear();
        telemetry.clear();
        telemetryBytes.set(0);
    }

    // ------------------------------------------------------------------ queues

    private void safetyNet(
            int session, SafetyNetEvent.Kind kind, long producerId, Duration age, @Nullable String reason) {
        Instant at = clock.instant();
        control(event(
                "onSafetyNetEvent",
                () -> named(
                        session,
                        named -> listener.onSafetyNetEvent(new SafetyNetEvent(
                                kind, named, producerId, producers.apply(producerId), age, reason, at)))));
    }

    /** Runs {@code callback} with the session the recovery actor knows by {@code id}; nothing for none. */
    private void named(int id, Consumer<OddsFeedSession> callback) {
        OddsFeedSession session = sessions.apply(id);
        if (session == null) {
            LOG.debug("No session {} to name; its event is not delivered", id);
            return;
        }
        callback.accept(session);
    }

    private void connection(ConnectionState state, @Nullable String reason, int attempt, long waitMillis) {
        var change = new ConnectionStateChange(state, reason, attempt, Duration.ofMillis(waitMillis), clock.instant());
        coalesce(Slot.CONNECTION, new Connection(change, state == ConnectionState.DOWN));
    }

    /**
     * Fills the slot, merged with what it holds, and moves its marker to the end of the control queue:
     * the slot's newest value is delivered where its newest report stands, so a connection loss
     * reported after a producer's status, and before its newest, is heard before that newest. The
     * marker of the report it replaces is taken out, so a slot never has more than one queued. Two
     * reports never fill at once: the second finds the first's marker queued, or already taken.
     */
    private void coalesce(Object slot, Pending pending) {
        if (closed) {
            return;
        }
        filling.lock();
        try {
            long marked = ++generation;
            var replaced = new long[] {-1};
            slots.compute(slot, (key, held) -> {
                if (held == null) {
                    return new Held(pending, marked);
                }
                replaced[0] = held.generation();
                return new Held(pending.after(held.pending()), marked);
            });
            afterFill.run();
            control.add(new Marker(slot, marked));
            if (replaced[0] >= 0) {
                control.remove(new Marker(slot, replaced[0]));
            }
        } finally {
            filling.unlock();
        }
        LockSupport.unpark(thread);
    }

    /** Room for {@code bytes} of raw data under the budget, taken; false, counted, when there is none. */
    private boolean reserve(long bytes) {
        long held;
        do {
            held = telemetryBytes.get();
            if (held + bytes > telemetryByteBudget) {
                long dropped = rawDataDropped.incrementAndGet();
                if (dropped == 1 || dropped % 1_000 == 0) {
                    LOG.warn(
                            "The events telemetry queue holds {} bytes of raw API data; a response of {} is dropped,"
                                    + " {} so far",
                            held,
                            bytes,
                            dropped);
                }
                return false;
            }
        } while (!telemetryBytes.compareAndSet(held, held + bytes));
        return true;
    }

    private void release(Event event) {
        if (event.bytes() > 0) {
            telemetryBytes.addAndGet(-event.bytes());
        }
    }

    private void control(Event event) {
        if (closed) {
            return;
        }
        if (controlSize.incrementAndGet() > controlCapacity) {
            controlSize.decrementAndGet();
            long dropped = controlDropped.incrementAndGet();
            // the first, then one in a thousand: a full queue is a wedged callback, and the watchdog says so
            if (dropped == 1 || dropped % 1_000 == 0) {
                LOG.warn("The events queue is full; {} dropped, {} so far", event.name(), dropped);
            }
            return;
        }
        control.add(event);
        LockSupport.unpark(thread);
    }

    /** Queues a telemetry event, making room by dropping the oldest. */
    private void telemetry(Event event) {
        if (closed) {
            return;
        }
        while (!telemetry.offer(event)) {
            Event oldest = telemetry.poll();
            if (oldest != null) {
                release(oldest);
                long dropped = telemetryDropped.incrementAndGet();
                if (dropped == 1 || dropped % 1_000 == 0) {
                    LOG.warn("The events telemetry queue is full; {} dropped, {} so far", oldest.name(), dropped);
                }
            }
        }
        LockSupport.unpark(thread);
    }

    // ------------------------------------------------------------------ the thread

    private void run() {
        while (!closed) {
            boolean worked = drainControl();
            for (int taken = 0; taken < TELEMETRY_PER_TURN && !closed && control.isEmpty(); taken++) {
                Event event = telemetry.poll();
                if (event == null) {
                    break;
                }
                try {
                    deliver(event);
                } finally {
                    // the response in flight counts against the budget until its callbacks are done
                    release(event);
                }
                worked = true;
            }
            if (!worked && !closed && control.isEmpty() && telemetry.isEmpty()) {
                LockSupport.parkNanos(this, IDLE_NANOS);
            }
        }
    }

    private boolean drainControl() {
        boolean taken = false;
        Entry entry;
        while (!closed && (entry = control.poll()) != null) {
            switch (entry) {
                case Marker(var slot, var markedAt) -> {
                    Held held = slots.get(slot);
                    afterRead.run();
                    // a marker the slot's newest report replaced, before it was taken out, is skipped,
                    // and so is one whose slot a newer report fills now: that one's marker is queued
                    if (held != null && held.generation() == markedAt && slots.remove(slot, held)) {
                        deliver(held.pending().event(this));
                    }
                }
                case Event event -> {
                    controlSize.decrementAndGet();
                    deliver(event);
                }
            }
            taken = true;
        }
        return taken;
    }

    private void deliver(Event event) {
        for (Call call : event.calls()) {
            if (closed) {
                return;
            }
            busySince = Math.max(1, clock.millis());
            try {
                call.run().run();
            } catch (Throwable e) {
                failed(call.callback(), e);
            } finally {
                busySince = 0;
                // an interrupt the client's code left would wake every later wait at once
                Thread.interrupted();
            }
        }
    }

    private void failed(String callback, Throwable e) {
        long failures = callbackFailures.incrementAndGet();
        if (failures == 1 || failures % 1_000 == 0) {
            LOG.error("The client's {} threw; {} callbacks have so far, the feed goes on", callback, failures, e);
        } else {
            LOG.debug("The client's {} threw", callback, e);
        }
        if (!callback.equals(ON_CALLBACK_FAILURE)) {
            callbackFailed(callback, true, e, null);
        }
    }

    private ProducerStatusMessage message(ProducerStatusChange change) {
        long at = change.timestamp();
        return new ProducerStatusMessage(
                producers.apply(change.producerId()),
                new MessageTimestamp(at, at, at, at),
                change.down(),
                change.delayed(),
                change.reason());
    }

    private ProducerCauseChange causeChange(ProducerStatusChange change) {
        return new ProducerCauseChange(
                change.producerId(),
                producers.apply(change.producerId()),
                change.down(),
                change.delayed(),
                change.reason(),
                change.cause().toPublic(),
                Instant.ofEpochMilli(change.timestamp()));
    }

    private static Event event(String callback, Runnable run) {
        return new Event(List.of(new Call(callback, run)));
    }

    // ------------------------------------------------------------------ what the queues hold

    /** One callback of the client's, named for the log and for the failure report. */
    private record Call(String callback, Runnable run) {}

    /** What the control queue holds: an event, or the marker of a slot with something in it. */
    private sealed interface Entry {}

    /** Callbacks delivered together, each guarded on its own. */
    private record Event(List<Call> calls, long bytes) implements Entry {
        Event(List<Call> calls) {
            this(calls, 0);
        }

        String name() {
            return calls.getFirst().callback();
        }
    }

    /** A slot's place in the control queue, for the report of this generation. */
    private record Marker(Object slot, long generation) implements Entry {}

    /** What a slot holds, and the generation of its newest report, whose marker is queued. */
    private record Held(Pending pending, long generation) {}

    /** The slots that are not a producer's. */
    private enum Slot {
        CONNECTION,
        BROKER_FATAL,
        API_FATAL
    }

    private record ProducerSlot(long producerId) {}

    private record CauseSlot(long producerId) {}

    private record LagSlot(int session) {}

    /** What a slot holds. */
    private sealed interface Pending {
        /** This, reported after {@code held}, which the slot still holds. */
        Pending after(Pending held);

        Event event(EventsDispatcher dispatcher);
    }

    /** The newest replaces what the slot holds. */
    private record Latest(Event latest) implements Pending {
        @Override
        public Pending after(Pending held) {
            return this;
        }

        @Override
        public Event event(EventsDispatcher dispatcher) {
            return latest;
        }
    }

    /** The connection's newest state, and whether it was lost since the client last heard. */
    private record Connection(ConnectionStateChange latest, boolean lost) implements Pending {
        @Override
        public Pending after(Pending held) {
            return new Connection(latest, lost || (held instanceof Connection before && before.lost));
        }

        @Override
        public Event event(EventsDispatcher dispatcher) {
            GlobalEventsListener listener = dispatcher.listener;
            var change = new Call("onConnectionStateChange", () -> listener.onConnectionStateChange(latest));
            return lost
                    ? new Event(List.of(new Call("onConnectionDown", listener::onConnectionDown), change))
                    : new Event(List.of(change));
        }
    }
}
