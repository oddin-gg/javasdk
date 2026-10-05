package com.oddin.oddsfeedsdk.internal.events;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.ConnectionState;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.net.URI;
import java.time.Duration;
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
 * connection's state, producer status, fatal errors and event recovery completions. It holds {@value
 * #CONTROL_CAPACITY}; an event with no room is counted and logged, never waited for. Of those, each
 * producer's status, the connection's state and each kind of fatal error have one slot, which the
 * newest fills: such an event replaces one still queued rather than queueing behind it. So none of
 * them is ever dropped, a client that falls behind hears the state as it is now rather than one long
 * gone - the last word of a producer is never the one that found no room - and a flood of them
 * takes one queued entry each. A connection loss replaced that way is still told, by {@code
 * onConnectionDown}. The telemetry queue carries the API calls, the failed callbacks and the raw API
 * data; it holds {@value #TELEMETRY_CAPACITY} and drops the oldest when full, counting them.
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
    /** How many telemetry events the thread delivers before it looks at the control queue again. */
    private static final int TELEMETRY_PER_TURN = 100;
    /** How long the thread waits for an event before it looks again, should a wake-up be missed. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long close() waits for the thread to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private static final String ON_CALLBACK_FAILURE = "onCallbackFailure";

    private final GlobalEventsListener listener;
    private final @Nullable OddsFeedExtListener extListener;
    private final ProducerManager producers;
    private final InstantSource clock;
    private final int controlCapacity;

    /** The control events and the slots' markers, in the order they came. */
    private final Queue<Entry> control = new ConcurrentLinkedQueue<>();
    /** How many control events are queued, not counting the slots' markers. */
    private final AtomicInteger controlSize = new AtomicInteger();
    /** What each slot holds that is not delivered yet; a slot with something in it has one marker queued. */
    private final Map<Object, Pending> slots = new ConcurrentHashMap<>();

    private final Queue<Event> telemetry;

    private final AtomicLong controlDropped = new AtomicLong();
    private final AtomicLong telemetryDropped = new AtomicLong();
    private final AtomicLong callbackFailures = new AtomicLong();

    private final Thread thread;
    private volatile boolean closed;
    /** When the callback running now started, epoch millis, 0 between callbacks; for the watchdog. */
    private volatile long busySince;

    /**
     * @param extListener the client's extended listener, null for none
     * @param producers what a producer status message names its producer from
     */
    public EventsDispatcher(
            GlobalEventsListener listener, @Nullable OddsFeedExtListener extListener, ProducerManager producers) {
        this(listener, extListener, producers, InstantSource.system(), CONTROL_CAPACITY, TELEMETRY_CAPACITY);
    }

    /** With the clock and the queue sizes a test sets. */
    EventsDispatcher(
            GlobalEventsListener listener,
            @Nullable OddsFeedExtListener extListener,
            ProducerManager producers,
            InstantSource clock,
            int controlCapacity,
            int telemetryCapacity) {
        this.listener = listener;
        this.extListener = extListener;
        this.producers = producers;
        this.clock = clock;
        this.controlCapacity = controlCapacity;
        this.telemetry = new ArrayBlockingQueue<>(telemetryCapacity);
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
    public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
        control(event("onEventRecoveryCompleted", () -> listener.onEventRecoveryCompleted(eventId, requestId)));
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
        telemetry(new Event(List.of(
                new Call("onRawApiDataReceived", () -> ext.onRawApiDataReceived(uri, decoded)),
                new Call("onRawApiDataBytes", () -> ext.onRawApiDataBytes(uri, body)))));
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

    /** Telemetry events dropped, the oldest first, for want of room. */
    public long telemetryDropped() {
        return telemetryDropped.get();
    }

    /** Callbacks that threw. */
    public long callbackFailures() {
        return callbackFailures.get();
    }

    /** Stops delivering. Events still queued are dropped, and so is anything reported from now on. */
    @Override
    public void close() {
        closed = true;
        if (thread.isAlive()) {
            LockSupport.unpark(thread);
            try {
                if (!thread.join(CLOSE_WAIT)) {
                    LOG.warn("The events dispatcher did not stop within {}: a callback still runs", CLOSE_WAIT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        control.clear();
        slots.clear();
        telemetry.clear();
    }

    // ------------------------------------------------------------------ queues

    private void connection(ConnectionState state, @Nullable String reason, int attempt, long waitMillis) {
        var change = new ConnectionStateChange(state, reason, attempt, Duration.ofMillis(waitMillis), clock.instant());
        coalesce(Slot.CONNECTION, new Connection(change, state == ConnectionState.DOWN));
    }

    /** Fills the slot, merged with what it holds; queues its marker only when it was empty. */
    private void coalesce(Object slot, Pending pending) {
        if (closed) {
            return;
        }
        var empty = new boolean[1];
        slots.compute(slot, (key, held) -> {
            if (held == null) {
                empty[0] = true;
                return pending;
            }
            return pending.after(held);
        });
        if (empty[0]) {
            control.add(new Marker(slot));
            LockSupport.unpark(thread);
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
                deliver(event);
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
                case Marker(var slot) -> {
                    Pending pending = slots.remove(slot);
                    if (pending != null) {
                        deliver(pending.event(this));
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
                producers.getProducer(change.producerId()),
                new MessageTimestamp(at, at, at, at),
                change.down(),
                change.delayed(),
                change.reason());
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
    private record Event(List<Call> calls) implements Entry {
        String name() {
            return calls.getFirst().callback();
        }
    }

    private record Marker(Object slot) implements Entry {}

    /** The slots that are not a producer's. */
    private enum Slot {
        CONNECTION,
        BROKER_FATAL,
        API_FATAL
    }

    private record ProducerSlot(long producerId) {}

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
