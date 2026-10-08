package com.oddin.oddsfeedsdk.internal.feed;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.LogCapture;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.session.SessionRegistry;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The watchdog finds a part stalled - one callback over its limit, a queue standing still, a
 * deadlock, its own tick overdue - tells it once, and tells it healthy again once that ends; it
 * never touches the thread.
 */
class WatchdogTest {

    private static final long WAIT_SECONDS = 10;
    private static final Duration SECOND = Duration.ofSeconds(1);
    /** The limits for an HTTP timeout of 5 s, the 30 s floor, and a look every 5 s. */
    private static final Watchdog.Limits FLOOR =
            new Watchdog.Limits(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5));
    /** The limits for the default HTTP timeout of 30 s: twice that and 5 s. */
    private static final Watchdog.Limits FOR_THE_DEFAULT_HTTP_TIMEOUT =
            new Watchdog.Limits(Duration.ofSeconds(65), Duration.ofSeconds(65), Duration.ofSeconds(5));

    private final OddsFeedSession first = new OddsFeedSession() {};
    private final Heard heard = new Heard();
    private final EventsDispatcher events = new EventsDispatcher(heard, null, id -> null, id -> null);
    private final FakeClock clock = new FakeClock();
    /** The changes the health logged, in order. */
    private final List<HealthEvent> logged = new CopyOnWriteArrayList<>();

    private final HealthMonitor health =
            new HealthMonitor(events, id -> id == 1 ? first : null, clock, Duration.ofHours(1), logged::add);

    /** What the watchdog reads at its next look; a test sets it. */
    private volatile List<Watchdog.Sample> samples = List.of();
    /** What the watchdog's search finds deadlocked; a test sets it. */
    private volatile List<String> deadlocked = List.of();

    /** Every watchdog a test makes, stopped and waited for after it, however it ends. */
    private final List<Watchdog> made = new CopyOnWriteArrayList<>();
    /** The timer threads alive before the test: those of no watchdog of this test. */
    private final Set<Thread> timersBefore = timerThreads();

    private final AtomicInteger readings = new AtomicInteger();
    private final Watchdog watchdog =
            made(new Watchdog(health, () -> samples, readings::incrementAndGet, () -> deadlocked, clock::nanos, FLOOR));

    @AfterEach
    void close() {
        heard.release();
        for (Watchdog each : made) {
            each.stop();
        }
        for (Watchdog each : made) {
            assertThat(each.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                    .as("a watchdog's timer thread ended after its test")
                    .isTrue();
        }
        events.close();
    }

    @Test
    void aCallbackOverItsLimitStallsTheSessionOnceAndItsReturnMakesItHealthy() throws InterruptedException {
        events.start();
        long taken = clock.nanos();
        samples = List.of(session(taken, 0, 7));
        watchdog.tick();
        clock.advance(Duration.ofSeconds(30));
        watchdog.tick();
        heard.nothingMore("a callback of 30 s, the limit");
        assertThat(logged).isEmpty();

        clock.advance(Duration.ofSeconds(5));
        watchdog.tick();
        var stalled = heard.next();
        assertThat(stalled.component()).isEqualTo(HealthComponent.SESSION);
        assertThat(stalled.session()).isSameAs(first);
        assertThat(stalled.previous()).isEqualTo(HealthState.HEALTHY);
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).isEqualTo("session 1 has been in one message for 35 s");
        assertThat(sessionState()).isEqualTo(HealthState.STALLED);

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(5));
            watchdog.tick();
        }
        heard.nothingMore("still stalled: told once");
        assertThat(logged).as("logged once").containsExactly(stalled);

        samples = List.of(session(0, 0, 8));
        clock.advance(Duration.ofSeconds(5));
        watchdog.tick();
        var healthy = heard.next();
        assertThat(healthy.previous()).isEqualTo(HealthState.STALLED);
        assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(healthy.reason()).isEqualTo("session 1 moves again");
        assertThat(sessionState()).isEqualTo(HealthState.HEALTHY);
        assertThat(logged).containsExactly(stalled, healthy);
        assertThat(readings).as("the whole health read at each look").hasValue(8);
    }

    @Test
    void withAnHttpTimeoutOf30sACallbackWaitingOnASlowApiIsNoStall() throws InterruptedException {
        var watched = made(new Watchdog(
                health, () -> samples, () -> {}, () -> deadlocked, clock::nanos, FOR_THE_DEFAULT_HTTP_TIMEOUT));
        events.start();
        // a callback reading a match the API is slow to load: two loads of 31 s, and a second more
        samples = List.of(session(clock.nanos(), 3, 7));
        watched.tick();
        clock.advance(Duration.ofSeconds(63));
        watched.tick();
        clock.advance(SECOND);
        watched.tick();
        heard.nothingMore("in one callback, its queue still, for 64 s, under the limit of 65 s");
        assertThat(sessionState()).isEqualTo(HealthState.HEALTHY);

        clock.advance(Duration.ofSeconds(2));
        watched.tick();
        var stalled = heard.next();
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).isEqualTo("session 1 has been in one message for 66 s");
    }

    @Test
    void aQueueThatStandsStillForItsLimitStallsItsPartUntilItMoves() throws InterruptedException {
        events.start();
        samples = List.of(session(0, 3, 7));
        watchdog.tick();
        clock.advance(Duration.ofSeconds(29));
        watchdog.tick();
        heard.nothingMore("still for 29 s");

        clock.advance(SECOND);
        watchdog.tick();
        var stalled = heard.next();
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).isEqualTo("session 1 has 3 waiting and has taken none for 30 s");

        samples = List.of(session(0, 3, 8));
        clock.advance(Duration.ofSeconds(5));
        watchdog.tick();
        assertThat(heard.next().state()).as("it took one").isEqualTo(HealthState.HEALTHY);

        // still again, from this look on
        clock.advance(Duration.ofSeconds(29));
        watchdog.tick();
        heard.nothingMore("still again for 29 s");
        clock.advance(SECOND);
        watchdog.tick();
        assertThat(heard.next().state()).isEqualTo(HealthState.STALLED);
    }

    @Test
    void aQueueThatMovesOrIsEmptyNeverStalls() throws InterruptedException {
        events.start();
        for (int look = 0; look < 30; look++) {
            // the session moves at each look; the alives have nothing waiting, and take nothing
            samples = List.of(
                    session(0, 100, look),
                    new Watchdog.Sample(HealthComponent.ALIVES, 0, "the alive dispatcher", "one alive", 0, 0, 5));
            watchdog.tick();
            clock.advance(Duration.ofSeconds(5));
        }
        heard.nothingMore("nothing stood still while something waited");
    }

    @Test
    void anEmptyQueueThatFillsIsStillOnlyFromTheLookThatSawItFill() throws InterruptedException {
        events.start();
        samples = List.of(session(0, 0, 7));
        watchdog.tick();
        clock.advance(Duration.ofMinutes(5));
        samples = List.of(session(0, 2, 7));
        watchdog.tick();
        heard.nothingMore("just filled");
        clock.advance(Duration.ofSeconds(30));
        watchdog.tick();
        assertThat(heard.next().reason()).endsWith("has taken none for 30 s");
    }

    @Test
    void aWedgedEventsListenerIsFoundStalledAndLoggedThoughItsOwnEventWaitsBehindTheWedge()
            throws InterruptedException {
        var watching = new Watchdog(
                health,
                () -> Watchdog.parts(events, null),
                () -> {},
                List::of,
                System::nanoTime,
                new Watchdog.Limits(Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofHours(1)));
        events.start();
        heard.wedge();
        events.called(new ApiCall(
                "GET", URI.create("https://api.example.invalid/v1/users/whoami"), 200, Duration.ZERO, 1, null));
        heard.awaitWedged();
        Thread.sleep(300);
        watching.tick();
        assertThat(logged).hasSize(1);
        var stalled = logged.getFirst();
        assertThat(stalled.component()).isEqualTo(HealthComponent.EVENTS);
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).startsWith("the events thread has been in one callback for ");
        assertThat(read().components()).containsEntry(HealthComponent.EVENTS, HealthState.STALLED);
        watching.tick();
        assertThat(logged).as("logged once").hasSize(1);

        heard.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (events.busySince() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        watching.tick();
        assertThat(logged).hasSize(2);
        assertThat(logged.getLast().state()).isEqualTo(HealthState.HEALTHY);
        assertThat(read().components()).containsEntry(HealthComponent.EVENTS, HealthState.HEALTHY);
        // the stall waited in its slot behind the wedge: the client hears it late and then the return,
        // or, when the return replaced it still queued, nothing, as it was healthy all along
        var told = heard.drain();
        assertThat(told)
                .extracting(HealthEvent::state)
                .isIn(List.of(), List.of(HealthState.STALLED, HealthState.HEALTHY));
    }

    @Test
    void aDeadlockOnLocksStallsTheThreadsUntilItEnds() throws InterruptedException {
        var real = new Watchdog(health, List::of, () -> {}, Watchdog::deadlockedThreads, clock::nanos, FLOOR);
        events.start();
        real.tick();
        heard.nothingMore("no deadlock");

        var one = new ReentrantLock();
        var other = new ReentrantLock();
        var holding = new CountDownLatch(2);
        var threads = List.of(
                Thread.ofPlatform().daemon().name("deadlock-one").start(() -> deadlock(one, other, holding)),
                Thread.ofPlatform().daemon().name("deadlock-other").start(() -> deadlock(other, one, holding)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (Watchdog.deadlockedThreads().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            real.tick();
            var stalled = heard.next();
            assertThat(stalled.component()).isEqualTo(HealthComponent.THREADS);
            assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
            assertThat(stalled.reason()).isEqualTo("a deadlock holds the threads deadlock-one, deadlock-other");
            assertThat(read().components()).containsEntry(HealthComponent.THREADS, HealthState.STALLED);
        } finally {
            // the test's own remedy: the watchdog never interrupts a thread
            threads.forEach(Thread::interrupt);
            for (Thread thread : threads) {
                thread.join(Duration.ofSeconds(WAIT_SECONDS));
            }
        }
        real.tick();
        var healthy = heard.next();
        assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(healthy.reason()).isEqualTo("no deadlock holds a thread");
    }

    @Test
    void aDeadlockOnMonitorsIsFoundInAJvmOfItsOwn() throws Exception {
        // a deadlock on monitors never ends, so it is made where it ends with its JVM
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), MonitorDeadlock.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            assertThat(process.waitFor(WAIT_SECONDS * 3, TimeUnit.SECONDS))
                    .as("the JVM with the deadlock ended")
                    .isTrue();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(out)
                    .contains("THREADS HEALTHY -> STALLED: a deadlock holds the threads monitor-one, monitor-other");
            assertThat(process.exitValue()).as(out).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void aTickOverdueStallsTheTimersOnAReadAndTheNextTickMakesThemHealthy() throws InterruptedException {
        var timed = made(new Watchdog(
                health,
                List::of,
                () -> {},
                List::of,
                clock::nanos,
                new Watchdog.Limits(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(10))));
        events.start();
        timed.recheck();
        timed.start();
        clock.advance(Duration.ofMinutes(10).plusSeconds(30));
        timed.recheck();
        heard.nothingMore("the next tick 30 s late");

        clock.advance(SECOND);
        timed.recheck();
        var stalled = heard.next();
        assertThat(stalled.component()).isEqualTo(HealthComponent.TIMERS);
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
        assertThat(stalled.reason()).isEqualTo("the watchdog has not looked for 631 s, its thread wedged");
        timed.recheck();
        heard.nothingMore("told once");

        timed.tick();
        var healthy = heard.next();
        assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(healthy.reason()).isEqualTo("the watchdog looks again");

        timed.stop();
        clock.advance(Duration.ofHours(1));
        timed.recheck();
        heard.nothingMore("a watchdog stopped is no watchdog late");
        assertThat(timed.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();
    }

    @Test
    void aWatchdogStoppedWithItsTimersStalledLeavesThemHealthyAndTheOtherPartsAsFound() throws InterruptedException {
        var timed = made(new Watchdog(
                health,
                () -> samples,
                () -> {},
                List::of,
                clock::nanos,
                new Watchdog.Limits(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(10))));
        events.start();
        timed.start();
        samples = List.of(session(clock.nanos(), 0, 7));
        clock.advance(Duration.ofMinutes(11));
        timed.recheck();
        assertThat(heard.drain())
                .extracting(HealthEvent::component, HealthEvent::state)
                .containsExactlyInAnyOrder(
                        tuple(HealthComponent.SESSION, HealthState.STALLED),
                        tuple(HealthComponent.TIMERS, HealthState.STALLED));

        timed.stop();
        var healthy = heard.next();
        assertThat(healthy.component()).isEqualTo(HealthComponent.TIMERS);
        assertThat(healthy.previous()).isEqualTo(HealthState.STALLED);
        assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(healthy.reason()).isEqualTo("the watchdog stopped");
        assertThat(read().components())
                .as("a watchdog stopped is not one wedged")
                .containsEntry(HealthComponent.TIMERS, HealthState.HEALTHY);
        assertThat(sessionState()).as("the session as last found").isEqualTo(HealthState.STALLED);

        timed.stop();
        clock.advance(Duration.ofHours(1));
        timed.recheck();
        heard.nothingMore("stopped again, read again: nothing changed");
        assertThat(read().components()).containsEntry(HealthComponent.TIMERS, HealthState.HEALTHY);
        assertThat(timed.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();
    }

    @Test
    void aReadFindsTheWatchdogsOwnThreadWedgedAndItsTickReturningTellsItHealthy() throws InterruptedException {
        var ticked = new AtomicInteger();
        var timed = made(new Watchdog(
                health,
                List::of,
                ticked::incrementAndGet,
                List::of,
                System::nanoTime,
                new Watchdog.Limits(Duration.ofHours(1), Duration.ofMillis(300), Duration.ofMillis(50))));
        events.start();
        timed.start();
        awaitAtLeast(ticked, 3);

        var wedged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        timed.beforeTick(() -> {
            wedged.countDown();
            await(release);
        });
        assertThat(wedged.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(500);
        timed.recheck();
        var stalled = heard.next();
        assertThat(stalled.component()).isEqualTo(HealthComponent.TIMERS);
        assertThat(stalled.state()).isEqualTo(HealthState.STALLED);

        timed.beforeTick(() -> {});
        release.countDown();
        var healthy = heard.next();
        assertThat(healthy.component()).isEqualTo(HealthComponent.TIMERS);
        assertThat(healthy.state()).as("told by the tick that returned").isEqualTo(HealthState.HEALTHY);
        assertThat(threadAlive()).isTrue();

        timed.stop();
        assertThat(timed.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();
        int stoppedAt = ticked.get();
        Thread.sleep(500);
        assertThat(ticked).as("no tick once stopped").hasValue(stoppedAt);
        assertThat(threadAlive()).as("the timer thread ended").isFalse();
        timed.recheck();
        heard.nothingMore("stopped");
    }

    @Test
    void aLookThatFailsEndsNoLaterLook() throws InterruptedException {
        var failed = new AtomicInteger();
        var ticked = new AtomicInteger();
        var timed = made(new Watchdog(
                health,
                () -> {
                    if (failed.getAndIncrement() == 0) {
                        throw new IllegalStateException("a part that cannot be read");
                    }
                    return List.of();
                },
                ticked::incrementAndGet,
                List::of,
                System::nanoTime,
                new Watchdog.Limits(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(20))));
        timed.start();
        try {
            awaitAtLeast(ticked, 2);
            assertThat(failed.get()).isGreaterThanOrEqualTo(3);
        } finally {
            timed.stop();
        }
    }

    /** A look that fails at every tick: its stack once a minute, and debug between, not one line a tick. */
    @Test
    void aLookThatFailsEveryTickIsLoggedOnceAMinute() throws InterruptedException {
        var ticked = new AtomicInteger();
        var timed = made(new Watchdog(
                health,
                List::of,
                () -> {},
                () -> {
                    throw new IllegalStateException("a deadlock search that cannot run");
                },
                System::nanoTime,
                new Watchdog.Limits(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(20))));
        timed.beforeTick(ticked::incrementAndGet);
        try (var log = LogCapture.of(Watchdog.class, Level.DEBUG)) {
            timed.start();
            try {
                awaitAtLeast(ticked, 4);
            } finally {
                timed.stop();
            }
            assertThat(timed.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                    .isTrue();
            assertThat(log.lines().stream()
                            .filter(line -> line.startsWith("ERROR "))
                            .count())
                    .as("the first look, and none for the next minute")
                    .isEqualTo(1);
            assertThat(log.lines().stream()
                            .filter(line -> line.startsWith("DEBUG "))
                            .count())
                    .as("the rest of the looks")
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void aWatchdogStartsOnceAndNotAfterItsStop() throws InterruptedException {
        var ticked = new AtomicInteger();
        var timed = made(new Watchdog(
                health,
                List::of,
                ticked::incrementAndGet,
                List::of,
                System::nanoTime,
                new Watchdog.Limits(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(200))));
        timed.start();
        long first = System.nanoTime();
        timed.start();
        assertThat(timerThreadsOfThisTest())
                .as("one timer thread for two starts")
                .hasSize(1);
        awaitAtLeast(ticked, 3);
        long took = System.nanoTime() - first;
        assertThat(ticked.get())
                .as("ticks of one schedule only, in %s ms", TimeUnit.NANOSECONDS.toMillis(took))
                .isLessThanOrEqualTo((int) (took / Duration.ofMillis(200).toNanos()) + 1);
        timed.stop();
        assertThat(timed.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();
        timed.start();
        assertThat(timerThreadsOfThisTest()).as("no start after a stop").isEmpty();

        watchdog.stop();
        watchdog.start();
        assertThat(timerThreadsOfThisTest()).as("no start after a stop").isEmpty();
        assertThat(watchdog.awaitStop(System.nanoTime())).as("never started").isTrue();
    }

    @Test
    void itReadsTheEventsThreadFromTheStartAndWhatTheOpenBuiltOnceOpen() {
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(configuration, events, client -> {});
            try {
                assertThat(Watchdog.parts(events, null))
                        .extracting(Watchdog.Sample::component)
                        .containsExactly(HealthComponent.EVENTS);

                var registry = new SessionRegistry(null);
                registry.builder()
                        .setListener(new HealthMonitorTest.Silent())
                        .setMessageInterest(MessageInterest.LIVE_ONLY)
                        .build();
                registry.builder()
                        .setListener(new HealthMonitorTest.Silent())
                        .setMessageInterest(MessageInterest.PREMATCH_ONLY)
                        .build();
                var live = OpenFeed.build(
                        core,
                        Sessions.plan(registry.open(), core.producers().getAvailableProducers(), null),
                        configuration,
                        health);
                try {
                    var parts = Watchdog.parts(events, live);
                    assertThat(parts)
                            .extracting(Watchdog.Sample::component, Watchdog.Sample::session, Watchdog.Sample::name)
                            .containsExactly(
                                    tuple(HealthComponent.EVENTS, 0, "the events thread"),
                                    tuple(HealthComponent.CONSUMER, 0, "a consumer thread"),
                                    tuple(HealthComponent.SESSION, 1, "session 1"),
                                    tuple(HealthComponent.SESSION, 2, "session 2"),
                                    tuple(HealthComponent.ALIVES, 0, "the alive dispatcher"),
                                    tuple(HealthComponent.RECOVERY, 0, "the recovery actor"));
                    assertThat(parts)
                            .as("nothing started: nothing busy, waiting or taken")
                            .allSatisfy(part -> assertThat(part.busySince()).isZero());
                    var actor = requireNonNull(live.actor());
                    assertThat(parts.getLast().queued()).isEqualTo(actor.pending());
                    assertThat(parts.getLast().moved()).isEqualTo(actor.turns());
                } finally {
                    live.close();
                }

                var replays = new SessionRegistry(null);
                replays.builder().setListener(new HealthMonitorTest.Silent()).buildReplay();
                var replay = OpenFeed.build(
                        core,
                        Sessions.plan(replays.open(), core.producers().getAvailableProducers(), null),
                        configuration,
                        health);
                try {
                    assertThat(Watchdog.parts(events, replay))
                            .extracting(Watchdog.Sample::component)
                            .as("a replay feed has no alives nor recovery")
                            .containsExactly(HealthComponent.EVENTS, HealthComponent.CONSUMER, HealthComponent.SESSION);
                } finally {
                    replay.close();
                }
            } finally {
                core.close();
            }
        }
    }

    // ------------------------------------------------------------------ support

    private Watchdog.Sample session(long busySince, long queued, long moved) {
        return new Watchdog.Sample(HealthComponent.SESSION, 1, "session 1", "one message", busySince, queued, moved);
    }

    /** The session's state as the health reads it now. */
    private HealthState sessionState() {
        var reading = health.assess(
                health.nextReading(),
                true,
                true,
                true,
                new FeedHealth.Transport(true, 0),
                new FeedHealth.Alives(0, 0, 0, 0),
                List.of(new FeedHealth.Session(1, first, HealthState.HEALTHY, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                new FeedHealth.Recovery(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                new FeedHealth.Caches(0, 0, 0, 0, 0, 0),
                new FeedHealth.Events(0, 0, 0, 0));
        return reading.sessions().getFirst().state();
    }

    private FeedHealth read() {
        return health.assess(
                health.nextReading(),
                true,
                false,
                false,
                new FeedHealth.Transport(false, 0),
                new FeedHealth.Alives(0, 0, 0, 0),
                List.of(),
                new FeedHealth.Recovery(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                new FeedHealth.Caches(0, 0, 0, 0, 0, 0),
                new FeedHealth.Events(0, 0, 0, 0));
    }

    /** A watchdog this test made, stopped and waited for after the test. */
    private Watchdog made(Watchdog watchdog) {
        made.add(watchdog);
        return watchdog;
    }

    /** Whether a timer thread of this test's watchdogs is alive: one alive before the test is not. */
    private boolean threadAlive() {
        return !timerThreadsOfThisTest().isEmpty();
    }

    private Set<Thread> timerThreadsOfThisTest() {
        var now = timerThreads();
        now.removeAll(timersBefore);
        return now;
    }

    private static Set<Thread> timerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("oddsfeed-timer") && thread.isAlive())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static void awaitAtLeast(AtomicInteger count, int times) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (count.get() < times && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(count.get()).isGreaterThanOrEqualTo(times);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Takes the first lock, waits for the other thread to take its own, then the second: never. */
    private static void deadlock(ReentrantLock first, ReentrantLock second, CountDownLatch holding) {
        first.lock();
        try {
            holding.countDown();
            holding.await();
            second.lockInterruptibly();
            second.unlock();
        } catch (InterruptedException e) {
            // the deadlock's end
        } finally {
            first.unlock();
        }
    }

    /**
     * A clock a test moves, the wall clock's and {@link System#nanoTime}'s alike. Its nanos begin
     * below 0 and pass it, as System.nanoTime's may.
     */
    private static final class FakeClock implements InstantSource {
        private volatile Instant now = Instant.parse("2026-10-07T12:00:00Z");
        private final AtomicLong nanos = new AtomicLong(-Duration.ofSeconds(10).toNanos());

        @Override
        public Instant instant() {
            return now;
        }

        long nanos() {
            return nanos.get();
        }

        void advance(Duration by) {
            now = now.plus(by);
            nanos.addAndGet(by.toNanos());
        }
    }

    /** Hears the health events, and can wedge the events thread in its next API call. */
    private static final class Heard implements GlobalEventsListener {
        private final BlockingQueue<HealthEvent> events = new LinkedBlockingQueue<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean wedge;

        HealthEvent next() throws InterruptedException {
            return requireNonNull(events.poll(WAIT_SECONDS, TimeUnit.SECONDS), "a health event");
        }

        void nothingMore(String why) throws InterruptedException {
            @Nullable HealthEvent more = events.poll(200, TimeUnit.MILLISECONDS);
            assertThat(more).as(why).isNull();
        }

        /** What is heard within a moment from now. */
        List<HealthEvent> drain() throws InterruptedException {
            var drained = new ArrayList<HealthEvent>();
            @Nullable HealthEvent next;
            while ((next = events.poll(300, TimeUnit.MILLISECONDS)) != null) {
                drained.add(next);
            }
            return drained;
        }

        void wedge() {
            wedge = true;
        }

        void awaitWedged() throws InterruptedException {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the events thread wedged")
                    .isTrue();
        }

        void release() {
            released.countDown();
        }

        @Override
        public void onApiCall(ApiCallEvent call) {
            if (wedge) {
                wedge = false;
                entered.countDown();
                await(released);
            }
        }

        @Override
        public void onHealthEvent(HealthEvent event) {
            events.add(event);
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }

    /**
     * Run in a JVM of its own: two threads deadlocked on monitors, and the watchdog's look, which
     * prints what the health logs.
     */
    static final class MonitorDeadlock {
        private static final Object ONE = new Object();
        private static final Object OTHER = new Object();

        public static void main(String[] args) throws InterruptedException, IOException {
            var holding = new CountDownLatch(2);
            Thread.ofPlatform().daemon().name("monitor-one").start(() -> hold(ONE, OTHER, holding));
            Thread.ofPlatform().daemon().name("monitor-other").start(() -> hold(OTHER, ONE, holding));
            var events = new EventsDispatcher(new Heard(), null, id -> null);
            var health = new HealthMonitor(
                    events,
                    id -> null,
                    change -> System.out.println(change.component() + " " + change.previous() + " -> " + change.state()
                            + ": " + change.reason()));
            var watchdog =
                    new Watchdog(health, List::of, () -> {}, Watchdog::deadlockedThreads, System::nanoTime, FLOOR);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (Watchdog.deadlockedThreads().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            watchdog.tick();
            System.out.flush();
            // the deadlocked threads are daemons: the JVM ends with them held
            System.exit(0);
        }

        private static void hold(Object first, Object second, CountDownLatch holding) {
            synchronized (first) {
                holding.countDown();
                try {
                    holding.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (second) {
                    holding.countDown();
                }
            }
        }
    }
}
