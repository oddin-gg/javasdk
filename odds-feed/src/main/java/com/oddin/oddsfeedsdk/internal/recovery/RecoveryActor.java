package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.RecoveryRequests;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recovery actor: one thread that owns all producer and recovery state, and runs the {@link
 * RecoveryMachine} over the facts the rest of the feed posts. Nobody else touches that state, so it
 * needs no lock; whoever posts only puts a fact in a bounded queue and returns, and a fact with no
 * room is dropped and counted. The AMQP consumer thread never posts here at all: the dispatchers do.
 *
 * <p>Two queues: the samples - the messages and alives a session finished, many and each worth
 * little - and the control facts, few and each needed. The actor takes every control fact before
 * the next samples, so a flood of samples cannot hold up a snapshot complete or an answer from the
 * API. It looks at the time at least every {@link RecoverySettings#tick()}.
 *
 * <p>The requests go to REST workers, never run here; their answers come back as facts, the only
 * ones that wait, briefly, for room. A safety-net reset runs on a worker too, since replacing a
 * channel talks to the broker.
 *
 * <p>Safe for concurrent use.
 */
public final class RecoveryActor implements AliveFacts, ConnectionEvents, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryActor.class);

    static final int CONTROL_CAPACITY = 10_000;
    static final int SAMPLE_CAPACITY = 10_000;
    /** How many samples the actor takes before it looks at the control facts again. */
    private static final int SAMPLES_PER_TURN = 1_000;
    /** How long a REST worker waits for room for an answer, which must not be lost lightly. */
    private static final Duration ANSWER_WAIT = Duration.ofSeconds(1);
    /** How long close() waits for the actor's thread to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final RecoveryMachine machine;
    private final RecoveryCounters counters;
    private final BlockingQueue<Fact> control;
    private final BlockingQueue<Fact> samples;
    private final RecoveryRequests api;
    private final Executor workers;
    private final InstantSource clock;
    private final long tickMillis;
    /** The sessions' channels, for the safety net's resets; the actor's thread's only. */
    private final Map<Integer, SessionTransport> transports = new HashMap<>();

    private final Thread thread;
    private volatile boolean closed;
    /** When the actor last began a turn, epoch millis; for the watchdog. */
    private volatile long turnedAt;

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
        post(control, new Fact.Opened(info, transport));
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
                post(control, new Fact.SnapshotComplete(id, producerId, requestId));
            }

            @Override
            public void channelLost() {
                post(control, new Fact.ChannelLost(id));
            }

            @Override
            public void closed() {
                post(control, new Fact.Closed(id));
            }
        };
    }

    /** The feed is open: the actor starts, the sessions opened so far miss everything before now. */
    public void start() {
        post(control, new Fact.Start());
        thread.start();
    }

    @Override
    public void alive(long producerId, long generatedAt, long receivedAt, boolean subscribed) {
        post(control, new Fact.Alive(producerId, generatedAt, receivedAt, subscribed));
    }

    @Override
    public void up() {
        post(control, new Fact.Connection(true));
    }

    @Override
    public void down(String reason) {
        post(control, new Fact.Connection(false));
    }

    /**
     * Asks for one event's odds, or with {@code stateful} its stateful messages, again. The result
     * is the request id once the API has accepted the request, or null when it did not, too many
     * are in flight or the actor is closed; it fails for a producer the list does not have. It
     * completes on the actor's thread or a worker, so whoever waits for it waits on its own thread,
     * with a deadline.
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

    private boolean post(BlockingQueue<Fact> queue, Fact fact) {
        if (closed) {
            return false;
        }
        if (!queue.offer(fact)) {
            dropped(fact);
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
        long nextTick = clock.millis();
        try {
            while (!closed) {
                turnedAt = clock.millis();
                boolean worked = drain(control, Integer.MAX_VALUE);
                worked |= drain(samples, SAMPLES_PER_TURN);
                long now = clock.millis();
                if (now >= nextTick) {
                    handle(new Fact.Tick());
                    nextTick = now + tickMillis;
                }
                if (!worked && control.isEmpty() && samples.isEmpty() && !closed) {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(Math.max(1, nextTick - now)));
                }
            }
        } finally {
            closeMachine();
        }
    }

    private void closeMachine() {
        for (Fact fact : control) {
            if (fact instanceof Fact.RecoverEvent recover) {
                recover.reply().complete(null);
            }
        }
        control.clear();
        samples.clear();
        machine.close();
    }

    private boolean drain(BlockingQueue<Fact> queue, int most) {
        int taken = 0;
        Fact fact;
        while (taken < most && !closed && (fact = queue.poll()) != null) {
            handle(fact);
            taken++;
        }
        return taken > 0;
    }

    /** One fact, to the end; a fact the machine fails on is counted, and the actor goes on. */
    private void handle(Fact fact) {
        try {
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
                case Fact.Alive(var producer, var generated, var received, var subscribed) ->
                    machine.alive(producer, generated, received, subscribed);
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
                case Fact.RecoverEvent(var producer, var event, var stateful, var reply) ->
                    machine.recoverEvent(producer, event, stateful, reply);
                case Fact.Tick() -> machine.tick();
            }
        } catch (RuntimeException e) {
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
            } catch (RejectedExecutionException e) {
                answer(new Fact.Answered(call.requestId(), e));
            }
        }

        @Override
        public void reset(int session) {
            SessionTransport transport = transports.get(session);
            if (transport == null) {
                return;
            }
            try {
                workers.execute(() -> {
                    int dropped = transport.queue().size();
                    try {
                        transport.reset();
                        counters.resetDropped.addAndGet(dropped);
                    } catch (RuntimeException e) {
                        LOG.warn("The safety net could not reset session {}", session, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.warn("The safety net could not reset session {}: the workers are closed", session);
            }
        }

        /** On a worker: the call, then its answer back to the actor. */
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
            }
            answer(new Fact.Answered(call.requestId(), failure));
        }

        private void answer(Fact.Answered answer) {
            if (closed) {
                return;
            }
            try {
                if (control.offer(answer, ANSWER_WAIT.toNanos(), TimeUnit.NANOSECONDS)) {
                    LockSupport.unpark(thread);
                } else {
                    dropped(answer);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropped(answer);
            }
        }
    }

    /**
     * The events, kept from breaking the machine: one that throws is logged, and the transition it
     * came from finishes.
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
            } catch (RuntimeException e) {
                LOG.error("The recovery events listener threw on {}; the actor goes on", event, e);
            }
        }
    }

    /** What the actor's queues carry. */
    private sealed interface Fact {
        record Opened(SessionInfo info, SessionTransport transport) implements Fact {}

        record Closed(int session) implements Fact {}

        record Start() implements Fact {}

        record Alive(long producerId, long generatedAt, long receivedAt, boolean subscribed) implements Fact {}

        record Processed(int session, long producerId, long generatedAt, long takenAt, boolean snapshot)
                implements Fact {}

        record SessionAlive(int session, long producerId, long generatedAt, long takenAt, boolean subscribed)
                implements Fact {}

        record SnapshotComplete(int session, long producerId, long requestId) implements Fact {}

        record ChannelLost(int session) implements Fact {}

        record Connection(boolean up) implements Fact {}

        record Answered(long requestId, @Nullable Exception failure) implements Fact {}

        record RecoverEvent(long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply)
                implements Fact {}

        record Tick() implements Fact {}
    }
}
