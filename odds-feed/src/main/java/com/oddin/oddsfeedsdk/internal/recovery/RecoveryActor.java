package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.RecoveryRequests;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
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
 * the alives, snapshot completes, lost and reopened channels, the API's answers and finished resets - are never
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
    /**
     * How many of the request ids it asked for the actor remembers: far more than can be in flight,
     * a recovery per producer and 128 event recoveries each, so a snapshot complete one awaits is
     * never taken for a stray one.
     */
    static final int ISSUED_KEPT = 10_000;
    /**
     * How long close(deadline) waits for the thread past a deadline already spent: enough for the
     * fact being handled to end and the points to be taken back, which take microseconds.
     */
    static final Duration CLOSE_FLOOR = Duration.ofMillis(200);
    /** How long close() waits for the actor's thread to end, unless given a deadline. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    /**
     * How long the actor, closing, spends at most on the essential facts already queued: well
     * within {@link #CLOSE_WAIT}, so the machine still closes before close() stops waiting.
     */
    private static final Duration FINISH_WAIT = Duration.ofSeconds(2);

    private final RecoveryMachine machine;
    private final RecoveryCounters counters;
    /** The event recoveries' statuses: the machine writes them, on the actor's thread. */
    private final EventRecoveryStatuses statuses;

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

    /**
     * The request ids the actor has asked for, the last {@link #ISSUED_KEPT}: a snapshot complete of
     * any other is one no recovery of this feed awaits, and is dropped as it is posted. Written by
     * the actor's thread only, read by any.
     */
    private final Set<Long> issued = ConcurrentHashMap.newKeySet();
    /** The same ids, oldest first, for taking the oldest out; the actor's thread only. */
    private final ArrayDeque<Long> issuedOrder = new ArrayDeque<>();
    /**
     * The snapshot completes queued and not handled yet, one per session, producer and request: the
     * same one again meanwhile would change nothing, and is not queued twice.
     */
    private final Set<Fact.SnapshotComplete> completesQueued = ConcurrentHashMap.newKeySet();

    private final Thread thread;
    /**
     * Where the actor is in its life, moved by compare-and-set only, so it starts at most once and
     * its machine is closed exactly once: by close() when it never started, else by its thread.
     */
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);

    private volatile boolean closed;
    /** The deadline of the close, by {@link System#nanoTime}; null before it. */
    private volatile @Nullable Long closeBy;
    /** When the actor last began a turn, epoch millis; for the watchdog. */
    private volatile long turnedAt;
    /** A test's hook, run before each take from the samples, after the look at the essential facts. */
    volatile Runnable beforeSamplePoll = () -> {};
    /** A test's hook, run inside the handling of each fact, with the fact. */
    volatile Consumer<Fact> beforeHandle = fact -> {};
    /** A test's hook, run before each take from the event recovery requests. */
    volatile Runnable beforeRequestPoll = () -> {};
    /** A test's hook, run in start() once it has the start, before the thread starts. */
    volatile Runnable beforeThreadStart = () -> {};
    /** How long the closing actor waits between its looks at the essential posts under way. */
    private static final long POSTING_POLL_NANOS = TimeUnit.MICROSECONDS.toNanos(50);
    /** The essential posts between their look at the close and their offer. */
    private final AtomicInteger posting = new AtomicInteger();
    /** Whether the closing actor has waited for an essential post under way; for a test. */
    private volatile boolean awaitingPosts;
    /** A test's hook, run in an essential post after its look at the close, before what it queues. */
    volatile Runnable beforeEssentialOffer = () -> {};
    /** How long the actor spends on the facts queued when it closes; a test shortens it. */
    volatile Duration finishWait = FINISH_WAIT;
    /** A test's hook, run as the machine closes, on the thread that closes it. */
    volatile Runnable beforeMachineClose = () -> {};
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
        this.statuses = new EventRecoveryStatuses(clock, counters);
        this.machine = new RecoveryMachine(
                producers, settings, new Work(), new Guarded(events), clock, counters, statuses, random);
        this.thread = Thread.ofPlatform().daemon().name("oddsfeed-recovery").unstarted(this::run);
    }

    /**
     * A session the actor follows, from before {@link #start} or opened since.
     *
     * @param transport its channel, which the safety net resets
     * @return where the session's dispatcher posts its facts
     */
    public SessionFacts openSession(SessionInfo info, SessionTransport transport) {
        postEssential(new Fact.Opened(info, transport));
        int id = info.id();
        return new SessionFacts() {
            @Override
            public void processed(long producerId, long generatedAt, long takenAt, long requestId) {
                post(samples, new Fact.Processed(id, producerId, generatedAt, takenAt, requestId));
            }

            @Override
            public void alive(long producerId, long generatedAt, long takenAt, boolean subscribed) {
                post(samples, new Fact.SessionAlive(id, producerId, generatedAt, takenAt, subscribed));
            }

            @Override
            public void snapshotComplete(long producerId, long requestId) {
                // essential facts are never dropped, so a flood of them is stopped here: one of a
                // request this feed never asked for completes nothing, and one queued already is
                // all the actor needs of it
                if (!issued.contains(requestId)) {
                    long stray = counters.unknownCompletions.incrementAndGet();
                    if (stray == 1 || stray % 1_000 == 0) {
                        LOG.warn(
                                "A snapshot complete of request {}, which this feed did not ask for, is dropped; {} so far",
                                requestId,
                                stray);
                    }
                    return;
                }
                var complete = new Fact.SnapshotComplete(id, producerId, requestId);
                if (completesQueued.add(complete) && !postEssential(complete)) {
                    completesQueued.remove(complete);
                }
            }

            @Override
            public void channelLost() {
                postEssential(new Fact.ChannelLost(id));
            }

            @Override
            public void channelReopened() {
                postEssential(new Fact.ChannelReopened(id));
            }

            @Override
            public void closed() {
                postEssential(new Fact.Closed(id));
            }
        };
    }

    /**
     * The feed is open: the actor starts, the sessions opened so far miss everything before now. It
     * asks for nothing until the transport's first {@link #up}, which comes once every session's
     * channel is bound: a recovery sent before would reach no queue.
     *
     * <p>Once only: a second start does nothing, and so does a start after {@link #close}, whose
     * actor stays closed with its thread never run.
     */
    public void start() {
        if (!lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STARTED)) {
            return;
        }
        postEssential(new Fact.Start());
        beforeThreadStart.run();
        thread.start();
    }

    /**
     * An alive, never dropped but never queued one by one either: it joins its producer's slot, and
     * the slot is queued once until the actor takes it. A flood of alives costs one slot per
     * producer of the list; one of a producer the list does not have is counted and dropped.
     */
    @Override
    public void alive(long producerId, long generatedAt, long receivedAt, boolean subscribed) {
        // one essential post, counted from its look at the close until its slot and fact are in:
        // the closing actor waits for it, or takes the points back without it
        posting.incrementAndGet();
        try {
            if (closed) {
                return;
            }
            beforeEssentialOffer.run();
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
                // no second look at the close: counted since the first, the fact is waited for
                essential.offer(new Fact.Alives(producerId));
            }
        } finally {
            posting.decrementAndGet();
        }
        LockSupport.unpark(thread);
    }

    /**
     * The feed has begun to close: from now on the published resume points only go back, so the
     * sessions it closes next cannot move a point the client persists at shutdown past what one of
     * them had not processed. The feed tells this before it closes any session; {@link #close} does
     * it too, before the facts still queued.
     */
    public void closing() {
        postEssential(new Fact.Closing());
    }

    @Override
    public void up() {
        postEssential(new Fact.Connection(true));
    }

    @Override
    public void down(String reason) {
        postEssential(new Fact.Connection(false));
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

    /**
     * Where the event recovery with this request id is - pending, completed, failed or timed out -
     * or null for an id the actor never asked for, or one that ended more than five minutes ago.
     * Producer recoveries have none. Never waits for the actor.
     */
    public @Nullable EventRecoveryStatus recoveryStatus(long requestId) {
        return statuses.get(requestId);
    }

    /** Whether the actor's thread runs, for a test. */
    boolean running() {
        return thread.isAlive();
    }

    /** Whether the actor's thread was ever started, for a test. */
    boolean threadStarted() {
        return thread.getState() != Thread.State.NEW;
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
     * Stops the actor. The essential facts already queued are handled first, so the resume points
     * are published as they stand; whoever waits for an event recovery hears it was not accepted;
     * facts posted from now on are dropped.
     *
     * <p>Never started, the machine is closed here, and no start runs it later. Started, its thread
     * closes it as it ends, and this waits for that - unless the close came between the start and
     * the thread's own start: the thread then ends at once and closes it without this waiting.
     */
    @Override
    public void close() {
        close(System.nanoTime() + CLOSE_WAIT.toNanos());
    }

    /**
     * {@link #close()} within {@code deadline}, by {@link System#nanoTime}: the feed's one shutdown
     * deadline, which its sessions have had their share of. The facts queued are handled until then
     * at most; those left take the points back, as when their time is up. It waits {@link
     * #CLOSE_FLOOR} at least, even past the deadline, for the thread to end, so the published points
     * are final when it returns: past the deadline the thread only takes them back and closes the
     * machine.
     *
     * @return whether the actor has ended
     */
    public boolean close(long deadline) {
        // read by the thread once it sees the close
        closeBy = deadline;
        closed = true;
        if (lifecycle.getAndSet(Lifecycle.CLOSED) == Lifecycle.NEW) {
            closeMachine();
            return true;
        }
        if (!thread.isAlive()) {
            return true;
        }
        LockSupport.unpark(thread);
        try {
            if (thread.join(Duration.ofNanos(Math.max(CLOSE_FLOOR.toNanos(), deadline - System.nanoTime())))) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) {
            return true;
        }
        LOG.warn("The recovery actor did not stop in time");
        return false;
    }

    /**
     * Puts a fact in one of the lesser queues, never waiting; false when it was dropped or the actor
     * is closed. The essential facts go through {@link #postEssential}.
     */
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

    /**
     * An essential fact, counted from before its look at the close until it is queued, so the
     * closing actor, which waits until none is counted, finds every one queued before the close: a
     * fact either sees the close, and is dropped as one posted after it, or is in the queue by then.
     * Never taken back, since the actor then handles it. Its queue has no capacity of its own.
     */
    private boolean postEssential(Fact fact) {
        posting.incrementAndGet();
        try {
            if (closed) {
                return false;
            }
            beforeEssentialOffer.run();
            essential.offer(fact);
        } finally {
            posting.decrementAndGet();
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
        // whether the loop ran: a close that came before it did not wait for this thread
        boolean ran = false;
        try {
            long nextTick = clock.millis();
            while (!closed) {
                ran = true;
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
            if (ran) {
                // a close() that waits for this thread: what is queued is handled before it returns,
                // never after
                machine.closing();
                if (!finishEssential()) {
                    machine.unhandledAtClose();
                }
            }
            closeMachine();
        }
    }

    /**
     * On the actor's thread as it ends, with the machine told the feed is closing: the essential
     * facts queued when the close came are handled, and the resume points they move published - a
     * session's close among them moves none forward. One of them can open a gap - an unsubscribed
     * alive, say - that takes the point back, and a client reads the point at shutdown to resume
     * from. Within {@link #FINISH_WAIT}, and the close's deadline; nothing goes out to the workers any more, and nothing posted
     * after the close is taken, since posting is refused once closed. The samples are dropped: one
     * could only move a point forward.
     *
     * <p>It first waits for the essential posts under way to be queued: a fact posted before the
     * close is then in the queue, and every later one has seen the close.
     *
     * @return whether every one was handled, a post under way included; when not, the machine takes
     *     the points back to where those left could have taken them, at the furthest
     */
    private boolean finishEssential() {
        long deadline = System.nanoTime() + finishWait.toNanos();
        Long by = closeBy;
        if (by != null && by - deadline < 0) {
            deadline = by;
        }
        // an essential fact posted before the close is queued once none is counted; one still
        // counted when the time is up is one this cannot handle
        while (posting.get() > 0) {
            awaitingPosts = true;
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            LockSupport.parkNanos(POSTING_POLL_NANOS);
        }
        Fact fact;
        while (System.nanoTime() - deadline < 0 && (fact = essential.poll()) != null) {
            handle(fact);
        }
        return essential.isEmpty();
    }

    /** Whether the closing actor has waited for an essential post under way, for a test. */
    boolean awaitedPosts() {
        return awaitingPosts;
    }

    /** Whether close() has begun, for a test. */
    boolean closeBegun() {
        return closed;
    }

    /**
     * Ends the machine. Taken one by one, so a fact posted meanwhile is either taken here or found
     * by its poster, who sees the close and takes it back.
     */
    private void closeMachine() {
        beforeMachineClose.run();
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
     * One fact, to the end, and the resume points it moved published, even those of a fact the
     * machine failed on part way. A fact the machine fails on, even with an error, is counted, and
     * the actor goes on: one bad fact must not end the recovery of every producer.
     */
    private void handle(Fact fact) {
        boolean failed = false;
        try {
            beforeHandle.accept(fact);
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
                case Fact.Processed(var session, var producer, var generated, var taken, var requestId) ->
                    machine.processed(session, producer, generated, taken, requestId);
                case Fact.SessionAlive(var session, var producer, var generated, var taken, var subscribed) ->
                    machine.sessionAlive(session, producer, generated, taken, subscribed);
                case Fact.SnapshotComplete(var session, var producer, var requestId) -> {
                    completesQueued.remove(fact);
                    machine.snapshotComplete(session, producer, requestId);
                }
                case Fact.ChannelLost(var session) -> machine.channelLost(session);
                case Fact.ChannelReopened(var session) -> machine.channelReopened(session);
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
                case Fact.Closing() -> machine.closing();
            }
        } catch (Throwable e) {
            failed = true;
            counters.factsFailed.incrementAndGet();
            LOG.error("The recovery actor failed on {}; it goes on with the next", fact, e);
        } finally {
            // whatever the fact moved, even one that failed part way, the client reads it from the
            // producer at once
            publishResumePoints(fact, failed);
        }
    }

    /** The points {@code fact} moved published; a failure fails the fact, counted once. */
    private void publishResumePoints(Fact fact, boolean counted) {
        try {
            machine.publishResumePoints();
        } catch (Throwable e) {
            if (!counted) {
                counters.factsFailed.incrementAndGet();
            }
            LOG.error("The recovery actor failed to publish what {} moved; it goes on with the next", fact, e);
        }
    }

    /** The machine's work, handed to the workers. Called on the actor's thread. */
    private final class Work implements Outbox {

        @Override
        public void request(Call call) {
            // remembered before it goes out, so its snapshot complete, which can only come after, is known
            if (issued.add(call.requestId())) {
                issuedOrder.addLast(call.requestId());
                if (issuedOrder.size() > ISSUED_KEPT) {
                    issued.remove(issuedOrder.removeFirst());
                }
            }
            if (closed) {
                // the facts the actor finishes as it closes: no answer would be taken any more
                return;
            }
            try {
                workers.execute(() -> send(call));
            } catch (RuntimeException e) {
                // turned away, or the workers broke: the request failed, and is asked for again
                postEssential(new Fact.Answered(call.requestId(), e));
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
            if (closed) {
                return;
            }
            SessionTransport transport = transports.get(session);
            try {
                workers.execute(() -> {
                    // the transport moves the epoch as it takes the old channel's deliveries out:
                    // the old queue is gone exactly when it moved, whatever came of the new one
                    long before = transport == null ? 0 : transport.epoch();
                    int dropped = transport == null ? 0 : transport.queue().size();
                    try {
                        if (transport != null) {
                            transport.reset();
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("The safety net could not reset session {}", session, e);
                    } finally {
                        boolean replaced = false;
                        if (transport != null && transport.epoch() != before) {
                            replaced = true;
                            counters.resetDropped.addAndGet(dropped);
                            awaitOpen(session, transport);
                        }
                        postEssential(new Fact.ResetDone(session, number, replaced));
                    }
                });
            } catch (RuntimeException | Error e) {
                LOG.warn("The safety net could not reset session {}: the workers turned it away", session, e);
                postEssential(new Fact.ResetDone(session, number, false));
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
                postEssential(new Fact.Answered(call.requestId(), failure));
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
        public void producerCause(ProducerStatusChange change) {
            guard("producerCause", () -> events.producerCause(change));
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

    private enum Lifecycle {
        NEW,
        STARTED,
        CLOSED
    }

    /** What the actor's queues carry; package-private for a test's hook. */
    sealed interface Fact {
        record Opened(SessionInfo info, SessionTransport transport) implements Fact {}

        record Closed(int session) implements Fact {}

        record Start() implements Fact {}

        /** A producer's alive slot has something in it. */
        record Alives(long producerId) implements Fact {}

        record Processed(int session, long producerId, long generatedAt, long takenAt, long requestId)
                implements Fact {}

        record SessionAlive(int session, long producerId, long generatedAt, long takenAt, boolean subscribed)
                implements Fact {}

        record SnapshotComplete(int session, long producerId, long requestId) implements Fact {}

        record ChannelLost(int session) implements Fact {}

        record ChannelReopened(int session) implements Fact {}

        record Connection(boolean up) implements Fact {}

        record Answered(long requestId, @Nullable Exception failure) implements Fact {}

        /** A reset done; {@code replaced} false when the workers would not take it. */
        record ResetDone(int session, long number, boolean replaced) implements Fact {}

        record RecoverEvent(long producerId, URN eventId, boolean stateful, CompletableFuture<@Nullable Long> reply)
                implements Fact {}

        record Tick() implements Fact {}

        /** The feed has begun to close. */
        record Closing() implements Fact {}
    }
}
