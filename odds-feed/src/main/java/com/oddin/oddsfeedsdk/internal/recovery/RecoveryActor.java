package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.RecoveryRequests;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recovery actor: one thread that owns all producer and recovery state, and runs the {@link
 * RecoveryMachine} over the facts the rest of the feed posts. Nobody else touches that state, so it
 * needs no lock; whoever posts only puts a fact in a queue and returns, never waiting. The AMQP
 * consumer thread never posts here at all: the dispatchers do.
 *
 * <p>Three queues. The essential facts - sessions opening and closing, the start, the connection,
 * the alives, snapshot completes, lost channels, the API's answers and finished resets - are never
 * dropped, since losing one would leave the state wrong for good: an unsubscribed alive is the only
 * word of a gap. They keep their order. That queue has no capacity of its own: what fills it is
 * bounded by what the feed does and what the producers send of their own pace, its sessions, its
 * connections, its own requests and resets, an alive per producer every few seconds. The event
 * recovery requests are bounded, and one with no room is dropped, counted, and answered as not
 * accepted. The samples - the messages and alives a session finished, many and each worth little -
 * are bounded the same way. The actor takes the essential facts first, then the requests, then the
 * samples, and the lesser queues yield as soon as an essential fact waits, so nothing posted after
 * an essential fact is handled before it, and a flood of samples holds up nothing else. It looks at
 * the time at least every {@link RecoverySettings#tick()}.
 *
 * <p>The requests go to REST workers, never run here, and their answers come back as facts. A
 * safety-net reset runs on a worker too, since replacing a channel talks to the broker, and always
 * reports back when it is done. A caller's future for an event recovery is completed on a thread of
 * its own, so nothing the caller chains to it runs on the actor.
 *
 * <p>Safe for concurrent use.
 */
public final class RecoveryActor implements AliveFacts, ConnectionEvents, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryActor.class);

    static final int CONTROL_CAPACITY = 10_000;
    static final int SAMPLE_CAPACITY = 10_000;
    /** How many samples the actor takes before it looks at the other facts again. */
    private static final int SAMPLES_PER_TURN = 1_000;
    /** How many event recovery requests it takes in a turn, so a stream of them holds up no tick. */
    private static final int REQUESTS_PER_TURN = 1_000;
    /** How often a reset's worker looks whether the session's channel is open again. */
    private static final long REOPEN_POLL_MILLIS = 100;
    /** How long close() waits for the actor's thread to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final RecoveryMachine machine;
    private final RecoveryCounters counters;
    private final Queue<Fact> essential = new ConcurrentLinkedQueue<>();
    private final Queue<Fact> control;
    private final Queue<Fact> samples;
    private final RecoveryRequests api;
    private final Executor workers;
    private final InstantSource clock;
    private final long tickMillis;
    /** The sessions' channels, for the safety net's resets; written by the actor's thread only. */
    private final Map<Integer, SessionTransport> transports = new ConcurrentHashMap<>();
    /**
     * The alives of each producer not handled yet, by producer: one slot each, and one fact in the
     * essential queue for it, however many alives arrive meanwhile.
     */
    private final Map<Long, AliveSlot> alives = new ConcurrentHashMap<>();

    private final Producers producers;

    private final Thread thread;
    private volatile boolean closed;
    /** When the actor last began a turn, epoch millis; for the watchdog. */
    private volatile long turnedAt;
    /** A test's hook, run before each take from the samples, after the look at the essential facts. */
    volatile Runnable beforeSamplePoll = () -> {};
    /** A test's hook, run inside the handling of each fact. */
    volatile Runnable beforeHandle = () -> {};
    /** A test's hook, run before each take from the event recovery requests. */
    volatile Runnable beforeRequestPoll = () -> {};
    /** The turns the actor has taken; for the watchdog and a test. */
    private final AtomicLong turns = new AtomicLong();

    /**
     * @param workers where the requests and the resets run: REST workers, virtual threads
     */
    public RecoveryActor(
            Producers producers,
            RecoverySettings settings,
            RecoveryRequests api,
            RecoveryEvents events,
            Executor workers) {
        this(
                producers,
                settings,
                api,
                events,
                workers,
                InstantSource.system(),
                RandomGenerator.getDefault(),
                CONTROL_CAPACITY,
                SAMPLE_CAPACITY);
    }

    /** With the clock, the random ids and the queue sizes a test sets. */
    RecoveryActor(
            Producers producers,
            RecoverySettings settings,
            RecoveryRequests api,
            RecoveryEvents events,
            Executor workers,
            InstantSource clock,
            RandomGenerator random,
            int controlCapacity,
            int sampleCapacity) {
        this.counters = new RecoveryCounters();
        this.producers = producers;
        this.control = new ArrayBlockingQueue<>(controlCapacity);
        this.samples = new ArrayBlockingQueue<>(sampleCapacity);
        this.api = api;
        this.workers = workers;
        this.clock = clock;
        this.tickMillis = Math.max(1, settings.tick().toMillis());
        this.machine =
                new RecoveryMachine(producers, settings, new Work(), new Guarded(events), clock, counters, random);
        this.thread = Thread.ofPlatform().daemon().name("oddsfeed-recovery").unstarted(this::run);
    }

    /**
     * A session the actor follows, from before {@link #start} or opened since.
     *
     * @param transport its channel, which the safety net resets
     * @return where the session's dispatcher posts its facts
     */
    public SessionFacts openSession(SessionInfo info, SessionTransport transport) {
        post(essential, new Fact.Opened(info, transport));
        int id = info.id();
        return new SessionFacts() {
            @Override
            public void processed(long producerId, long generatedAt, long takenAt, boolean snapshot) {
                post(samples, new Fact.Processed(id, producerId, generatedAt, takenAt, snapshot));
            }

            @Override
            public void alive(long producerId, long generatedAt, long takenAt, boolean subscribed) {
                post(samples, new Fact.SessionAlive(id, producerId, generatedAt, takenAt, subscribed));
            }

            @Override
            public void snapshotComplete(long producerId, long requestId) {
                post(essential, new Fact.SnapshotComplete(id, producerId, requestId));
            }

            @Override
            public void channelLost() {
                post(essential, new Fact.ChannelLost(id));
            }

            @Override
            public void closed() {
                post(essential, new Fact.Closed(id));
            }
        };
    }

    /** The feed is open: the actor starts, the sessions opened so far miss everything before now. */
    public void start() {
        post(essential, new Fact.Start());
        thread.start();
    }

    /**
     * An alive, never dropped but never queued one by one either: it joins its producer's slot, and
     * the slot is queued once until the actor takes it. A flood of alives costs one slot per
     * producer of the list; one of a producer the list does not have is counted and dropped.
     */
    @Override
    public void alive(long producerId, long generatedAt, long receivedAt, boolean subscribed) {
        if (closed) {
            return;
        }
        if (!producers.isKnown(producerId)) {
            long unknown = counters.unknownProducers.incrementAndGet();
            if (unknown == 1 || unknown % 1_000 == 0) {
                LOG.warn("An alive of producer {}, which the producer list does not have, is dropped", producerId);
            }
            return;
        }
        var alive = new AliveSlot.Seen(generatedAt, receivedAt, subscribed);
        var fresh = new boolean[1];
        alives.compute(producerId, (id, slot) -> {
            AliveSlot joined = slot;
            if (joined == null) {
                fresh[0] = true;
                joined = new AliveSlot(alive);
            }
            joined.add(alive);
            return joined;
        });
        if (fresh[0]) {
            post(essential, new Fact.Alives(producerId));
        }
    }

    @Override
    public void up() {
        post(essential, new Fact.Connection(true));
    }

    @Override
    public void down(String reason) {
        post(essential, new Fact.Connection(false));
    }

    /**
     * Asks for one event's odds, or with {@code stateful} its stateful messages, again. The result
     * is the request id once the API has accepted the request, or null when it did not, the
     * connection is down, too many are in flight or the actor is closed; it fails for a producer
     * the list does not have. It completes on a thread of its own, so whoever waits for it waits on
     * theirs, with a deadline, and what they chain to it never runs on the actor.
     */
    public CompletableFuture<@Nullable Long> recoverEvent(long producerId, URN eventId, boolean stateful) {
        var reply = new CompletableFuture<@Nullable Long>();
        if (!post(control, new Fact.RecoverEvent(producerId, eventId, stateful, reply))) {
            reply.complete(null);
        }
        return reply;
    }

    public RecoveryCounters counters() {
        return counters;
    }

    /** Whether the actor's thread runs, for a test. */
    boolean running() {
        return thread.isAlive();
    }

    /** The essential facts waiting, for a test. */
    int queued() {
        return essential.size();
    }

    /** How many turns the actor has taken. */
    public long turns() {
        return turns.get();
    }

    /** When the actor last began a turn, epoch millis by its clock, 0 before the first. */
    public long turnedAt() {
        return turnedAt;
    }

    /**
     * Stops the actor. Whoever waits for an event recovery hears it was not accepted; facts posted
     * from now on are dropped.
     */
    @Override
    public void close() {
        closed = true;
        if (thread.isAlive()) {
            LockSupport.unpark(thread);
            try {
                if (!thread.join(CLOSE_WAIT)) {
                    LOG.warn("The recovery actor did not stop within {}", CLOSE_WAIT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (thread.getState() == Thread.State.NEW) {
            // never started: nothing else runs the machine
            closeMachine();
        }
    }

    /** Puts a fact in its queue, never waiting; false when it was dropped or the actor is closed. */
    private boolean post(Queue<Fact> queue, Fact fact) {
        if (closed) {
            return false;
        }
        if (!queue.offer(fact)) {
            dropped(fact);
            return false;
        }
        if (closed && queue.remove(fact)) {
            // the close came between the check and the offer, and its last drain may be over
            return false;
        }
        LockSupport.unpark(thread);
        return true;
    }

    private void dropped(Fact fact) {
        long dropped = counters.factsDropped.incrementAndGet();
        // the first, then one in a thousand: a full queue is a wedged actor, and the watchdog says so
        if (dropped == 1 || dropped % 1_000 == 0) {
            LOG.warn(
                    "The recovery actor's queue is full; {} dropped, {} so far",
                    fact.getClass().getSimpleName(),
                    dropped);
        }
    }

    private void run() {
        try {
            long nextTick = clock.millis();
            while (!closed) {
                turnedAt = clock.millis();
                turns.incrementAndGet();
                boolean worked = drainEssential();
                worked |= drainLesser(control, REQUESTS_PER_TURN, beforeRequestPoll);
                worked |= drainLesser(samples, SAMPLES_PER_TURN, beforeSamplePoll);
                long now = clock.millis();
                if (now >= nextTick) {
                    handle(new Fact.Tick());
                    nextTick = now + tickMillis;
                }
                if (!worked && essential.isEmpty() && control.isEmpty() && samples.isEmpty() && !closed) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(Math.max(1, nextTick - now)));
                }
            }
        } catch (Throwable e) {
            LOG.error("The recovery actor stopped", e);
            throw e;
        } finally {
            // however the loop ended: nothing posts to a queue nobody takes from
            closed = true;
            closeMachine();
        }
    }

    /**
     * Ends the machine. Taken one by one, so a fact posted meanwhile is either taken here or found
     * by its poster, who sees the close and takes it back.
     */
    private void closeMachine() {
        Fact fact;
        while ((fact = control.poll()) != null) {
            if (fact instanceof Fact.RecoverEvent recover) {
                Replies.complete(recover.reply(), null);
            }
        }
        essential.clear();
        samples.clear();
        alives.clear();
        machine.close();
    }

    /**
     * Takes the essential facts there are.
     */
    private boolean drainEssential() {
        boolean taken = false;
        Fact fact;
        while (!closed && (fact = essential.poll()) != null) {
            handle(fact);
            taken = true;
        }
        return taken;
    }

    /**
     * Takes up to {@code most} facts of a lesser queue, yielding to the essential facts: nothing
     * posted after an essential fact is handled before it, so a sample from a new channel cannot move
     * a checkpoint before the loss of the old one is known. The essential queue is looked at after a
     * fact is taken, not only before: whoever posted the essential fact and then this one put the
     * essential fact in first, so once this one is taken, that one is there to see.
     *
     * @param beforePoll a test's hook, run between the look at the essential queue and the take
     */
    private boolean drainLesser(Queue<Fact> queue, int most, Runnable beforePoll) {
        int taken = 0;
        while (taken < most && !closed && essential.isEmpty()) {
            beforePoll.run();
            Fact fact = queue.poll();
            if (fact == null) {
                break;
            }
            drainEssential();
            if (!closed) {
                handle(fact);
            } else if (fact instanceof Fact.RecoverEvent recover) {
                // taken from the queue the close drains: answered here instead
                Replies.complete(recover.reply(), null);
            }
            taken++;
        }
        return taken > 0;
    }

    /**
     * One fact, to the end. A fact the machine fails on, even with an error, is counted, and the
     * actor goes on: one bad fact must not end the recovery of every producer.
     */
    private void handle(Fact fact) {
        try {
            beforeHandle.run();
            switch (fact) {
                case Fact.Opened(var info, var transport) -> {
                    transports.put(info.id(), transport);
                    machine.sessionOpened(info);
                }
                case Fact.Closed(var session) -> {
                    transports.remove(session);
                    machine.sessionClosed(session);
                }
                case Fact.Start() -> machine.start();
                case Fact.Alives(var producer) -> {
                    AliveSlot slot = alives.remove(producer);
                    if (slot != null) {
                        for (AliveSlot.Seen alive : slot.inOrder()) {
                            machine.alive(producer, alive.generatedAt(), alive.receivedAt(), alive.subscribed());
                        }
                    }
                }
                case Fact.Processed(var session, var producer, var generated, var taken, var snapshot) ->
                    machine.processed(session, producer, generated, taken, snapshot);
                case Fact.SessionAlive(var session, var producer, var generated, var taken, var subscribed) ->
                    machine.sessionAlive(session, producer, generated, taken, subscribed);
                case Fact.SnapshotComplete(var session, var producer, var requestId) ->
                    machine.snapshotComplete(session, producer, requestId);
                case Fact.ChannelLost(var session) -> machine.channelLost(session);
                case Fact.Connection(var up) -> {
                    if (up) {
                        machine.connectionUp();
                    } else {
                        machine.connectionDown();
                    }
                }
                case Fact.Answered(var requestId, var failure) -> machine.answered(requestId, failure);
                case Fact.ResetDone(var session, var number, var replaced) ->
                    machine.resetDone(session, number, replaced);
                case Fact.RecoverEvent(var producer, var event, var stateful, var reply) ->
                    machine.recoverEvent(producer, event, stateful, reply);
                case Fact.Tick() -> machine.tick();
            }
        } catch (Throwable e) {
            counters.factsFailed.incrementAndGet();
            LOG.error("The recovery actor failed on {}; it goes on with the next", fact, e);
        }
    }

    /** The machine's work, handed to the workers. Called on the actor's thread. */
    private final class Work implements Outbox {

        @Override
        public void request(Call call) {
            try {
                workers.execute(() -> send(call));
            } catch (RuntimeException e) {
                // turned away, or the workers broke: the request failed, and is asked for again
                post(essential, new Fact.Answered(call.requestId(), e));
            } catch (Error e) {
                post(
                        essential,
                        new Fact.Answered(call.requestId(), new IllegalStateException("the workers failed", e)));
            }
        }

        /**
         * Replaces the channel on a worker, then reports it done, whatever came of it - but only once
         * the session has an open channel again: one the transport could not open at once it opens
         * on its own later, and a recovery asked for before then would send to no one.
         */
        @Override
        public void reset(int session, long number) {
            SessionTransport transport = transports.get(session);
            try {
                workers.execute(() -> {
                    try {
                        if (transport != null) {
                            int dropped = transport.queue().size();
                            transport.reset();
                            counters.resetDropped.addAndGet(dropped);
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("The safety net could not reset session {}", session, e);
                    } finally {
                        if (transport != null) {
                            awaitOpen(session, transport);
                        }
                        post(essential, new Fact.ResetDone(session, number, true));
                    }
                });
            } catch (RuntimeException | Error e) {
                LOG.warn("The safety net could not reset session {}: the workers turned it away", session, e);
                post(essential, new Fact.ResetDone(session, number, false));
            }
        }

        /**
         * On a worker: waits until the session's channel is open, the session is gone or the actor
         * closes. A channel that could not be opened again is opened by the transport, with backoff.
         */
        private void awaitOpen(int session, SessionTransport transport) {
            try {
                while (!closed && transport.equals(transports.get(session)) && !transport.isOpen()) {
                    TimeUnit.MILLISECONDS.sleep(REOPEN_POLL_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void reply(CompletableFuture<@Nullable Long> reply, @Nullable Long requestId) {
            Replies.complete(reply, requestId);
        }

        @Override
        public void fail(CompletableFuture<@Nullable Long> reply, RuntimeException failure) {
            Replies.fail(reply, failure);
        }

        /** On a worker: the call, then its answer back to the actor, whatever came of it. */
        private void send(Call call) {
            Exception failure = null;
            try {
                switch (call) {
                    case Call.Snapshot snapshot ->
                        api.postRecovery(snapshot.producer(), snapshot.requestId(), snapshot.after());
                    case Call.Event event
                    when event.stateful() ->
                        api.postEventStatefulRecovery(event.producer(), event.eventId(), event.requestId());
                    case Call.Event event ->
                        api.postEventOddsRecovery(event.producer(), event.eventId(), event.requestId());
                }
            } catch (RuntimeException e) {
                failure = e;
            } catch (Error e) {
                failure = new IllegalStateException("the request failed with an error", e);
            } finally {
                post(essential, new Fact.Answered(call.requestId(), failure));
            }
        }
    }

    /**
     * The alives of one producer the actor has not taken yet, as much of them as the machine needs:
     * the last subscribed one before the first unsubscribed one, where a gap starts; that first
     * unsubscribed one, which opens the gap; and the latest. Guarded by its map's compute.
     */
    private static final class AliveSlot {
        record Seen(long generatedAt, long receivedAt, boolean subscribed) {}

        private Seen latest;
        private @Nullable Seen lastSubscribedBeforeUnsubscribed;
        private @Nullable Seen firstUnsubscribed;
        /** Whether an alive came after the first unsubscribed one. */
        private boolean afterUnsubscribed;

        AliveSlot(Seen first) {
            this.latest = first;
        }

        void add(Seen alive) {
            if (firstUnsubscribed != null) {
                afterUnsubscribed = true;
            } else if (alive.subscribed()) {
                lastSubscribedBeforeUnsubscribed = alive;
            } else {
                firstUnsubscribed = alive;
            }
            latest = alive;
        }

        /** What to hand the machine, in the order the alives came. */
        List<Seen> inOrder() {
            var seen = new ArrayList<Seen>(3);
            Seen before = lastSubscribedBeforeUnsubscribed;
            Seen unsubscribed = firstUnsubscribed;
            if (unsubscribed == null) {
                seen.add(latest);
                return seen;
            }
            if (before != null) {
                seen.add(before);
            }
            seen.add(unsubscribed);
            if (afterUnsubscribed) {
                seen.add(latest);
            }
            return seen;
        }
    }

    /** Completes callers' futures each on a thread of its own, never on the actor's. */
    private static final class Replies {
        private Replies() {}

        static void complete(CompletableFuture<@Nullable Long> reply, @Nullable Long requestId) {
            Thread.ofVirtual().name("oddsfeed-recovery-reply").start(() -> reply.complete(requestId));
        }

        static void fail(CompletableFuture<@Nullable Long> reply, RuntimeException failure) {
            Thread.ofVirtual().name("oddsfeed-recovery-reply").start(() -> reply.completeExceptionally(failure));
        }
    }

    /**
     * The events, kept from breaking the machine: one that throws, even an error, is logged, and the
     * transition it came from finishes.
     */
    private static final class Guarded implements RecoveryEvents {
        private final RecoveryEvents events;

        Guarded(RecoveryEvents events) {
            this.events = events;
        }

        @Override
        public void producerStatus(ProducerStatusChange change) {
            guard("producerStatus", () -> events.producerStatus(change));
        }

        @Override
        public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
            guard("eventRecoveryCompleted", () -> events.eventRecoveryCompleted(producerId, eventId, requestId));
        }

        @Override
        public void safetyNetReset(int session, long producerId, long ageMillis) {
            guard("safetyNetReset", () -> events.safetyNetReset(session, producerId, ageMillis));
        }

        @Override
        public void safetyNetRequestFailed(int session, long producerId, String reason) {
            guard("safetyNetRequestFailed", () -> events.safetyNetRequestFailed(session, producerId, reason));
        }

        @Override
        public void lagging(int session, boolean lagging) {
            guard("lagging", () -> events.lagging(session, lagging));
        }

        private static void guard(String event, Runnable tell) {
            try {
                tell.run();
            } catch (RuntimeException | Error e) {
                LOG.error("The recovery events listener threw on {}; the actor goes on", event, e);
            }
        }
    }

    /** What the actor's queues carry. */
    private sealed interface Fact {
        record Opened(SessionInfo info, SessionTransport transport) implements Fact {}

        record Closed(int session) implements Fact {}

        record Start() implements Fact {}

        /** A producer's alive slot has something in it. */
        record Alives(long producerId) implements Fact {}

        record Processed(int session, long producerId, long generatedAt, long takenAt, boolean snapshot)
                implements Fact {}

        record SessionAlive(int session, long producerId, long generatedAt, long takenAt, boolean subscribed)
                implements Fact {}

        record SnapshotComplete(int session, long producerId, long requestId) implements Fact {}

        record ChannelLost(int session) implements Fact {}

        record Connection(boolean up) implements Fact {}

        record Answered(long requestId, @Nullable Exception failure) implements Fact {}

        /** A reset done; {@code replaced} false when the workers would not take it. */
        record ResetDone(int session, long number, boolean replaced) implements Fact {}

        record RecoverEvent(long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply)
                implements Fact {}

        record Tick() implements Fact {}
    }
}
