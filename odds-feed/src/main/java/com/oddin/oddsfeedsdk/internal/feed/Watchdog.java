package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.internal.BusySince;
import com.oddin.oddsfeedsdk.internal.dispatch.AliveDispatcher;
import com.oddin.oddsfeedsdk.internal.dispatch.SessionDispatcher;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.log.Throttle;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SDK's watch over its own threads: every {@link Limits#tick}, on the feed's timer thread, it
 * reads each part that runs on a thread of its own - the events thread, and once the feed is open the
 * broker's consumer threads, each session, the alive dispatcher and the recovery actor - and the
 * JVM's threads for a deadlock that holds one of the SDK's own, and tells the health what it finds:
 * a part is {@link HealthState#STALLED} while it has been in one callback for longer than {@link
 * Limits#callback}, or its queue has stayed not empty without moving for {@link Limits#queue}, or a
 * deadlock holds a thread of the SDK; healthy again once that ends. A deadlock among the client's
 * own threads is none of these: the feed it leaves as it is. Each tick also reads the whole health, so a catalog stale for its
 * limit is found without a reader. The health logs each change and tells it to {@code
 * onHealthEvent}; the watchdog does nothing else: it never interrupts or ends a thread, since it
 * cannot unwedge the client's code - the remedy is to close the feed and open a new one.
 *
 * <p>It cannot see its own thread wedge, so {@link #recheck}, which {@code getHealth()} calls on the
 * caller's thread, reads the parts too, and finds the timers {@link HealthComponent#TIMERS stalled}
 * when the next tick is more than {@link Limits#queue} late. One per feed, started with the feed's
 * first start, stopped by its close.
 *
 * <p>Every duration it measures - a callback's, a queue's still time, the time since its last tick
 * - is one of {@link System#nanoTime}, so a change of the wall clock neither stalls a part nor hides
 * a stall.
 *
 * <p>Safe for concurrent use: the tick and every recheck read the parts and decide under one lock,
 * which only reads counters and flags, and note what they decide there in that order; what is told
 * - and logged - is told once out of it.
 */
public final class Watchdog {

    private static final Logger LOG = LoggerFactory.getLogger(Watchdog.class);
    /**
     * The start of the name of every thread the SDK starts, platform or virtual. A deadlock counts
     * only for the threads of the SDK it holds: one among the client's own threads is no stall of a feed.
     */
    static final String THREAD_PREFIX = "oddsfeed-";
    /** How often a look that fails is logged with its stack: at most once in this long. */
    private static final Duration LOOK_FAILURE_INTERVAL = Duration.ofMinutes(1);

    /**
     * The limits and the tick, as the configuration has them: the limits derived from the HTTP
     * timeout unless set, and never under the longest the SDK itself waits for the API.
     *
     * @param callback how long a part may be in one callback - a session's, the events thread's, a
     *     consumer thread's hand-off, the recovery actor's turn - before it is stalled
     * @param queue how long a part's queue may stay not empty without moving before it is stalled;
     *     also how late the watchdog's own next tick may be before the timers are
     * @param tick how often the watchdog looks
     */
    public record Limits(Duration callback, Duration queue, Duration tick) {
        /** The configuration's. */
        public static Limits of(OddsFeedConfiguration configuration) {
            return new Limits(
                    configuration.getCallbackStallLimit(),
                    configuration.getQueueStallLimit(),
                    configuration.getWatchdogInterval());
        }
    }

    /**
     * What the watchdog reads of one part, at one look.
     *
     * @param session the feed's number for a session, 0 for a part that is no session
     * @param name the part in words, for the reason told: "session 1", "the events thread"
     * @param busy what the part is busy with, for the reason told: "one callback"
     * @param busySince when the part began what it is busy with now, a {@link BusySince}; {@link
     *     BusySince#IDLE} when it is idle
     * @param queued what waits for the part
     * @param moved how much the part has taken: a count that grows while the queue moves
     * @param ended why the part's thread is gone, as the reason told; null while it runs. A part
     *     whose thread ended takes no turn again, so it is stalled from the look that finds it so,
     *     with no limit to wait for
     */
    public record Sample(
            HealthComponent component,
            int session,
            String name,
            String busy,
            long busySince,
            long queued,
            long moved,
            @Nullable String ended) {

        /** A part whose thread runs. */
        public Sample(
                HealthComponent component,
                int session,
                String name,
                String busy,
                long busySince,
                long queued,
                long moved) {
            this(component, session, name, busy, busySince, queued, moved, null);
        }
    }

    /** A part the watchdog follows from one look to the next. */
    private record Key(HealthComponent component, int session) {}

    /** What the watchdog has found of a part so far. */
    private static final class Followed {
        /** The state the watchdog last noted of the part. */
        HealthState state = HealthState.HEALTHY;
        /** Whether the watchdog has noted that the part's thread ended: the reason it noted is the true one. */
        boolean endNoted;
        /** The part's taken count when the watchdog first saw it not moving with something queued. */
        long moved = -1;
        /** Whether it is so: not moving with something queued, from {@link #stillSince} on. */
        boolean still;
        /** When the watchdog first saw it so, by {@link Watchdog#nanos}; meaningless unless {@link #still}. */
        long stillSince;
    }

    private final HealthMonitor health;
    private final Supplier<List<Sample>> parts;
    private final Runnable readHealth;
    private final Supplier<List<String>> deadlocks;
    /** {@link System#nanoTime}, or a test's. */
    private final LongSupplier nanos;
    /** The looks that failed, a line at most a minute: a look that fails every tick would otherwise log every tick. */
    private final Throttle lookFailures = new Throttle();

    private final Limits limits;

    /** Held to decide and note, never to tell: it only reads counters and flags. */
    private final ReentrantLock deciding = new ReentrantLock();
    /** Each part followed, by its key; guarded by {@link #deciding}. */
    private final Map<Key, Followed> followed = new HashMap<>();
    /** The state last noted of the SDK's threads, for a deadlock; guarded by {@link #deciding}. */
    private HealthState threads = HealthState.HEALTHY;
    /** The state last noted of the timers; guarded by {@link #deciding}. */
    private HealthState timers = HealthState.HEALTHY;

    /** When the last tick began, by {@link #nanos}; the start, before the first. */
    private volatile long tickedAt;
    /** Whether it runs: started and not stopped. Only then are the timers checked. */
    private volatile boolean running;

    private final ReentrantLock lifecycle = new ReentrantLock();
    /** The timer thread; null before the start. Guarded by {@link #lifecycle}. */
    private @Nullable ScheduledThreadPoolExecutor timer;
    /** The ticks, every one from the first on; null before the start. Guarded by {@link #lifecycle}. */
    private @Nullable ScheduledFuture<?> ticks;
    /** Whether it was stopped; it never starts after. Guarded by {@link #lifecycle}. */
    private boolean stopped;

    /** A test's hook, run on the timer thread at the start of each tick. */
    private volatile Runnable beforeTick = () -> {};

    /**
     * @param parts what the parts are now, read at each look: the feed's events thread, and once it
     *     is open, what its open built
     * @param readHealth reads the whole health, which tells what it finds: for a catalog's staleness
     */
    public Watchdog(HealthMonitor health, Supplier<List<Sample>> parts, Runnable readHealth, Limits limits) {
        this(health, parts, readHealth, Watchdog::deadlockedThreads, System::nanoTime, limits);
    }

    /** With the deadlocks and the time a test sets. */
    Watchdog(
            HealthMonitor health,
            Supplier<List<Sample>> parts,
            Runnable readHealth,
            Supplier<List<String>> deadlocks,
            LongSupplier nanos,
            Limits limits) {
        this.health = health;
        this.parts = parts;
        this.readHealth = readHealth;
        this.deadlocks = deadlocks;
        this.nanos = nanos;
        this.limits = limits;
    }

    /** The limits it looks with; for a test. */
    public Limits limits() {
        return limits;
    }

    // ------------------------------------------------------------------ life

    /** Starts looking, every tick from one tick from now, on a daemon thread of its own; once. */
    public void start() {
        lifecycle.lock();
        try {
            if (timer != null || stopped) {
                return;
            }
            var started = new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("oddsfeed-timer").factory());
            started.setRemoveOnCancelPolicy(true);
            started.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            tickedAt = nanos.getAsLong();
            running = true;
            long every = limits.tick().toNanos();
            ticks = started.scheduleWithFixedDelay(this::tickQuietly, every, every, TimeUnit.NANOSECONDS);
            timer = started;
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Stops looking, and returns at once; a tick under way ends on its own. The timers are healthy
     * from then on, told so if they were found stalled: a watchdog stopped is not one wedged, and its
     * thread, ended, is late for no tick. What it found of every other part stays as it last found
     * it, since nothing looks again.
     */
    public void stop() {
        @Nullable ScheduledThreadPoolExecutor stopping;
        lifecycle.lock();
        try {
            stopped = true;
            running = false;
            stopping = timer;
            if (ticks != null) {
                ticks.cancel(false);
            }
        } finally {
            lifecycle.unlock();
        }
        if (stopping != null) {
            stopping.shutdown();
        }
        tell(timersStopped());
    }

    /** Notes the timers healthy, if they were not; a look after, no longer running, leaves them so. */
    private List<HealthMonitor.Noted> timersStopped() {
        deciding.lock();
        try {
            if (timers == HealthState.HEALTHY) {
                return List.of();
            }
            timers = HealthState.HEALTHY;
            return List.of(health.note(HealthComponent.TIMERS, 0, HealthState.HEALTHY, "the watchdog stopped"));
        } finally {
            deciding.unlock();
        }
    }

    /**
     * Waits for the timer thread to end after a {@link #stop}, until {@code deadline}, by {@link
     * System#nanoTime}; says so in the log when it does not.
     *
     * @return whether it has ended, or never started
     */
    public boolean awaitStop(long deadline) {
        @Nullable ScheduledThreadPoolExecutor stopping;
        lifecycle.lock();
        try {
            stopping = timer;
        } finally {
            lifecycle.unlock();
        }
        if (stopping == null) {
            return true;
        }
        try {
            if (stopping.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (stopping.isTerminated()) {
            return true;
        }
        LOG.warn("The watchdog did not stop in time");
        return false;
    }

    /** Runs {@code hook} on the timer thread at the start of each tick from now on; for a test. */
    public void beforeTick(Runnable hook) {
        beforeTick = hook;
    }

    // ------------------------------------------------------------------ looking

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException | Error e) {
            // a look that fails must not end every later one: the executor would run no more
            if (lookFailures.dueEvery(nanos.getAsLong(), LOOK_FAILURE_INTERVAL)) {
                LOG.error("The watchdog's look failed; it looks again at the next tick", e);
            } else {
                LOG.debug("The watchdog's look failed; it looks again at the next tick", e);
            }
        }
    }

    /** One look, on the timer thread: the parts, the SDK's threads, the timers, then the whole health. */
    void tick() {
        beforeTick.run();
        tickedAt = nanos.getAsLong();
        // outside the lock: the search for a deadlock, the JVM's, is no read of a flag
        List<String> deadlocked = deadlocks.get();
        tell(decide(deadlocked));
        readHealth.run();
    }

    /**
     * Looks again on the caller's thread, for {@code getHealth()}: the parts, and the timers, should
     * the watchdog's own thread be the one that is wedged. Not the deadlock search, which takes the
     * JVM to a safepoint; the tick does that. Nothing once stopped, or before the start.
     */
    public void recheck() {
        if (!running) {
            return;
        }
        tell(decide(null));
    }

    /**
     * Reads the parts, decides each one's state and notes each change, in order, all under the lock:
     * a look that read its parts before another cannot decide after it.
     *
     * @param deadlocked the SDK's threads a deadlock holds; null when this look did not search
     */
    private List<HealthMonitor.Noted> decide(@Nullable List<String> deadlocked) {
        var notes = new ArrayList<HealthMonitor.Noted>();
        deciding.lock();
        try {
            List<Sample> samples = parts.get();
            long now = nanos.getAsLong();
            for (Sample sample : samples) {
                var part =
                        followed.computeIfAbsent(new Key(sample.component(), sample.session()), key -> new Followed());
                @Nullable String stall = stall(sample, part, now);
                var state = stall == null ? HealthState.HEALTHY : HealthState.STALLED;
                if (state != part.state) {
                    part.state = state;
                    part.endNoted = sample.ended() != null;
                    notes.add(health.note(
                            sample.component(),
                            sample.session(),
                            state,
                            stall == null ? sample.name() + " moves again" : stall));
                } else if (sample.ended() != null && !part.endNoted) {
                    // stalled already, for another reason, when the thread ended: the state says it, but
                    // the reason noted is not the true one, and nothing moves the part again to change it
                    part.endNoted = true;
                    notes.add(health.noteStalledFor(sample.component(), sample.session(), sample.ended()));
                }
            }
            if (deadlocked != null) {
                var state = deadlocked.isEmpty() ? HealthState.HEALTHY : HealthState.STALLED;
                if (state != threads) {
                    threads = state;
                    notes.add(health.note(
                            HealthComponent.THREADS,
                            0,
                            state,
                            deadlocked.isEmpty()
                                    ? "no deadlock holds a thread"
                                    : "a deadlock holds the SDK threads " + String.join(", ", deadlocked)));
                }
            }
            if (running) {
                // the next look was due a tick after the last began
                long since = now - tickedAt;
                var state = since - limits.tick().toNanos() > limits.queue().toNanos()
                        ? HealthState.STALLED
                        : HealthState.HEALTHY;
                if (state != timers) {
                    timers = state;
                    notes.add(health.note(
                            HealthComponent.TIMERS,
                            0,
                            state,
                            state == HealthState.STALLED
                                    ? "the watchdog has not looked for " + seconds(since) + ", its thread wedged"
                                    : "the watchdog looks again"));
                }
            }
        } finally {
            deciding.unlock();
        }
        return notes;
    }

    /** Why the part is stalled now, or null when it is not; follows its queue from look to look. */
    private @Nullable String stall(Sample sample, Followed part, long now) {
        if (sample.ended() != null) {
            // a thread that is gone is not in a turn, nor is its queue worth a limit: nothing moves it again
            return sample.ended();
        }
        @Nullable String stall = null;
        long busySince = sample.busySince();
        // by their difference: a reading of System.nanoTime may be negative
        if (busySince != BusySince.IDLE && now - busySince > limits.callback().toNanos()) {
            stall = sample.name() + " has been in " + sample.busy() + " for " + seconds(now - busySince);
        }
        if (sample.queued() <= 0) {
            // nothing waits: not still, however long it takes nothing
            part.moved = sample.moved();
            part.still = false;
        } else if (sample.moved() != part.moved || !part.still) {
            // it took something since the last look, or something waits since: still from now on, if at all
            part.moved = sample.moved();
            part.still = true;
            part.stillSince = now;
        } else if (now - part.stillSince >= limits.queue().toNanos() && stall == null) {
            stall = sample.name() + " has " + sample.queued() + " waiting and has taken none for "
                    + seconds(now - part.stillSince);
        }
        return stall;
    }

    private static void tell(List<HealthMonitor.Noted> notes) {
        notes.forEach(HealthMonitor.Noted::tell);
    }

    private static String seconds(long nanos) {
        return TimeUnit.NANOSECONDS.toSeconds(nanos) + " s";
    }

    // ------------------------------------------------------------------ what it reads

    /**
     * What the watchdog reads of the feed now: its events thread, and once it is open, the broker's
     * consumer threads, each session, and - unless it is a replay feed - the alive dispatcher and the
     * recovery actor.
     *
     * @param run what the open built; null before the feed opens
     */
    public static List<Sample> parts(EventsDispatcher events, @Nullable OpenFeed run) {
        var parts = new ArrayList<Sample>();
        parts.add(new Sample(
                HealthComponent.EVENTS,
                0,
                "the events thread",
                "one callback",
                events.busySince(),
                events.queued(),
                events.delivered()));
        if (run == null) {
            return parts;
        }
        var consumers = run.transport().consumerState();
        parts.add(new Sample(
                HealthComponent.CONSUMER,
                0,
                "a consumer thread",
                "one hand-off",
                consumers.busySince(),
                consumers.waiting(),
                consumers.taken()));
        for (SessionDispatcher session : run.sessions()) {
            parts.add(new Sample(
                    HealthComponent.SESSION,
                    session.id(),
                    "session " + session.id(),
                    "one message",
                    session.busySince(),
                    session.transport().queue().size(),
                    session.handled()));
        }
        AliveDispatcher alives = run.alives();
        if (alives != null) {
            // between two alives it takes no time worth a limit; its queue is what shows a wedge
            parts.add(new Sample(
                    HealthComponent.ALIVES,
                    0,
                    "the alive dispatcher",
                    "one alive",
                    BusySince.IDLE,
                    alives.queued(),
                    alives.handled()));
        }
        RecoveryActor actor = run.actor();
        if (actor != null) {
            // it begins a turn every second however idle, so a turn begun long ago is one not ended; a
            // thread that ended by a failure, not by the close, is stalled at once
            var endedBy = actor.endedBy();
            parts.add(new Sample(
                    HealthComponent.RECOVERY,
                    0,
                    "the recovery actor",
                    "one turn",
                    actor.turnedAt(),
                    actor.pending(),
                    actor.turns(),
                    endedBy == null ? null : "the recovery thread ended: " + endedBy));
        }
        return parts;
    }

    /**
     * The names of the SDK's threads a deadlock holds, on monitors or on locks; none when there is
     * none, or when it holds only threads of the client's. A thread is the SDK's when its name starts
     * with {@link #THREAD_PREFIX}: every thread the SDK starts is named so. The threads of another
     * feed in the JVM are the SDK's too, since all feeds name theirs alike.
     */
    static List<String> deadlockedThreads() {
        return allDeadlockedThreads().stream()
                .filter(name -> name.startsWith(THREAD_PREFIX))
                .toList();
    }

    /** The names of every thread of the JVM a deadlock holds, the client's too; none when there is none. */
    static List<String> allDeadlockedThreads() {
        var threads = ManagementFactory.getThreadMXBean();
        // a JVM that cannot follow the owners of locks still finds the deadlocks on monitors
        long @Nullable [] ids = threads.isSynchronizerUsageSupported()
                ? threads.findDeadlockedThreads()
                : threads.findMonitorDeadlockedThreads();
        if (ids == null) {
            return List.of();
        }
        return Arrays.stream(threads.getThreadInfo(ids))
                .filter(Objects::nonNull)
                .map(ThreadInfo::getThreadName)
                .sorted()
                .toList();
    }
}
