package com.oddin.oddsfeedsdk.internal.loader;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache;
import com.oddin.oddsfeedsdk.internal.cache.Field;
import com.oddin.oddsfeedsdk.internal.cache.Write;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.clock.SplitClock;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Single-flight, the deadlines of waiters, and loads that wait for each other. */
class LoaderTest {

    private static final Duration DEADLINE = Duration.ofMillis(500);
    private static final Duration MARGIN = Duration.ofMillis(200);

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void close() {
        virtualThreads.shutdownNow();
    }

    @Test
    void concurrentMissesShareOneFetch() throws Exception {
        var fetches = new AtomicInteger();
        var release = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    await(release);
                    return "value of " + key;
                },
                Duration.ofSeconds(5),
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var joins = countJoins(loader);

        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            callers.add(virtualThreads.submit(() -> loader.load("m1")));
        }
        waitUntil(() -> joins.get() == 20);
        release.countDown();
        for (Future<String> caller : callers) {
            assertThat(caller.get(5, TimeUnit.SECONDS)).isEqualTo("value of m1");
        }
        assertThat(fetches).hasValue(1);
        waitUntil(() -> loader.inFlight() == 0);

        assertThat(loader.load("m1")).isEqualTo("value of m1");
        assertThat(fetches).as("a later miss fetches afresh").hasValue(2);
    }

    @Test
    void waitersThatRunOutOfTimeFailAndStartNoFetchOfTheirOwn() throws Exception {
        var fetches = new AtomicInteger();
        var never = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    await(never);
                    return "late";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var joins = countJoins(loader);

        long started = System.nanoTime();
        Future<String> first = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> joins.get() == 1);
        Future<String> second = virtualThreads.submit(() -> loader.load("m1"));
        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(ApiException.class)
                .hasMessage("match m1 not loaded within 700 ms of its fetch starting");
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isBetween(Duration.ofMillis(650), Duration.ofSeconds(3));
        assertThat(fetches).as("the second caller joined the first fetch").hasValue(1);
        never.countDown();
    }

    @Test
    void aFetchThatOverranEveryWaiterIsLetGo() {
        var fetches = new AtomicInteger();
        var never = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        await(never);
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        assertThat(loader.load("m1"))
                .as("a fresh fetch, not the one still stuck")
                .isEqualTo("value");
        assertThat(fetches).hasValue(2);
        never.countDown();
    }

    @Test
    void manyWaitersThatRunOutOfTimeTogetherLeaveTheFetchToTheNextMiss() throws Exception {
        var fetches = new AtomicInteger();
        var never = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        await(never);
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var waiters = new java.util.ArrayList<Future<String>>();
        for (int i = 0; i < 20; i++) {
            waiters.add(virtualThreads.submit(() -> loader.load("m1")));
        }
        for (Future<String> waiter : waiters) {
            assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        }
        assertThat(loader.inFlight()).as("left until a miss replaces it").isEqualTo(1);
        assertThat(loader.load("m1")).as("a fresh fetch").isEqualTo("value");
        assertThat(fetches).hasValue(2);
        never.countDown();
    }

    @Test
    void aFlightLeftPastItsDeadlineIsAbandonedAndReplacedByTheNextMiss() throws Exception {
        var fetches = new AtomicInteger();
        var never = new CountDownLatch(1);
        var firstAbandoned = new AtomicReference<java.util.function.BooleanSupplier>();
        var loader = new Loader<String, String>(
                "match",
                (key, _, abandoned) -> {
                    if (fetches.incrementAndGet() == 1) {
                        firstAbandoned.set(abandoned);
                        await(never);
                        return "late";
                    }
                    return "fresh";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var joins = countJoins(loader);
        var failure = new AtomicReference<ApiException>();
        var stillInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                loader.load("m1");
            } catch (ApiException e) {
                failure.set(e);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        waitUntil(() -> joins.get() == 1);
        caller.interrupt();
        caller.join(5_000);
        assertThat(failure.get()).hasMessage("match m1: interrupted");
        assertThat(stillInterrupted).as("the interrupt is kept").isTrue();
        assertThat(loader.inFlight()).as("left without running out of time").isEqualTo(1);

        Thread.sleep(DEADLINE.plus(MARGIN).toMillis() + 100);
        assertThat(loader.load("m1")).as("a fresh fetch, not the expired one").isEqualTo("fresh");
        assertThat(fetches).hasValue(2);
        assertThat(firstAbandoned.get().getAsBoolean())
                .as("the expired fetch was abandoned")
                .isTrue();
        never.countDown();
    }

    @Test
    void aCallerThatJoinsLateSharesTheFlightsDeadline() throws Exception {
        var never = new CountDownLatch(1);
        // three seconds in all, the late caller two seconds in: wide enough for a loaded runner
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    await(never);
                    return "late";
                },
                Duration.ofMillis(2_500),
                Duration.ofMillis(500),
                virtualThreads,
                FeedClock.system());
        var joins = countJoins(loader);
        var failedAt = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        Future<?> first = virtualThreads.submit(() -> failsAt(loader, "first", failedAt));
        waitUntil(() -> joins.get() == 1);
        Thread.sleep(2_000);
        Future<?> late = virtualThreads.submit(() -> failsAt(loader, "late", failedAt));
        first.get(10, TimeUnit.SECONDS);
        late.get(10, TimeUnit.SECONDS);
        // with a deadline of its own the late caller would fail some 2 s after the first
        assertThat(Duration.ofNanos(
                        Math.abs(requireNonNull(failedAt.get("late")) - requireNonNull(failedAt.get("first")))))
                .as("both at the flight's one deadline")
                .isLessThan(Duration.ofSeconds(1));
        never.countDown();
    }

    @Test
    void theFetchIsGivenItsFlightsOneDeadlineForEveryCallItMakes() throws Exception {
        var given = new java.util.concurrent.CopyOnWriteArrayList<com.oddin.oddsfeedsdk.internal.rest.Deadline>();
        var release = new CountDownLatch(1);
        var clock = FeedClock.system();
        var loader = new Loader<String, String>(
                "match",
                (key, deadline, _) -> {
                    given.add(deadline);
                    await(release);
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                clock);
        var joins = countJoins(loader);
        long before = clock.monoNanos();
        Future<String> first = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> joins.get() == 1);
        long after = clock.monoNanos();
        Future<String> second = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> joins.get() == 2);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("value");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("value");

        assertThat(given).as("one fetch, one deadline").hasSize(1);
        var deadline = given.getFirst();
        assertThat(deadline.budget()).isEqualTo(DEADLINE);
        assertThat(deadline.endNanos())
                .as("the HTTP client timeout from when the flight started, without the callers' margin")
                .isBetween(before + DEADLINE.toNanos(), after + DEADLINE.toNanos());
    }

    @Test
    void aFetchPastItsCallersTimeIsAbandonedThoughNothingReplacedIt() throws Exception {
        var release = new CountDownLatch(1);
        var abandonedAtTheEnd = new java.util.concurrent.CompletableFuture<Boolean>();
        var loader = new Loader<String, String>(
                "match",
                (key, _, abandoned) -> {
                    boolean before = abandoned.getAsBoolean();
                    await(release);
                    abandonedAtTheEnd.complete(!before && abandoned.getAsBoolean());
                    return "late";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        Thread.sleep(100);
        release.countDown();
        assertThat(abandonedAtTheEnd.get(5, TimeUnit.SECONDS))
                .as("not before its time, and abandoned after it with no newer miss")
                .isTrue();
    }

    @Test
    void aLoadInsideAFetchWaitsNoLongerThanThatFetchsDeadline() {
        var never = new CountDownLatch(1);
        var competitors = new Loader<String, String>(
                "competitor",
                (key, _, _) -> {
                    await(never);
                    return "late";
                },
                Duration.ofSeconds(10),
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var matches = new Loader<String, String>(
                "match",
                (key, deadline, _) -> competitors.load("c1", deadline),
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        long started = System.nanoTime();
        assertThatThrownBy(() -> matches.load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("competitor c1 not loaded within its caller's deadline");
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("the match fetch's deadline, not the competitor's ten seconds")
                .isLessThan(Duration.ofSeconds(3));
        never.countDown();
    }

    @Test
    void aFailedFetchFailsEveryWaiterAndTheNextMissStartsAfresh() {
        var fetches = new AtomicInteger();
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        throw new ApiException("Failed to get data: 503");
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("503");
        assertThat(loader.load("m1")).as("a plain loader remembers no failure").isEqualTo("value");
    }

    @Test
    void anErrorFromAFetchReachesTheCallerAsItWas() {
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    throw new ExceptionInInitializerError("the binding did not load");
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ExceptionInInitializerError.class)
                .hasMessage("the binding did not load");
    }

    @Test
    void aFetchThatCannotStartLeavesNothingBehind() {
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> "value",
                DEADLINE,
                MARGIN,
                task -> {
                    throw new java.util.concurrent.RejectedExecutionException("closed");
                },
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("match m1: no fetch could start");
        assertThat(loader.inFlight()).isZero();
    }

    @Test
    void aCallerThatJoinedAFetchThatCannotStartGetsTheSameFailure() throws Exception {
        var joined = new CountDownLatch(1);
        var loader = new AtomicReference<Loader<String, String>>();
        loader.set(new Loader<>(
                "match",
                (key, _, _) -> "value",
                DEADLINE,
                MARGIN,
                task -> {
                    await(joined);
                    throw new java.util.concurrent.RejectedExecutionException("closed");
                },
                FeedClock.system()));
        var joins = countJoins(loader.get());
        Future<String> first = virtualThreads.submit(() -> loader.get().load("m1"));
        // the first is held inside its execute, after it joined: then the second joins its flight
        waitUntil(() -> joins.get() == 1);
        Future<String> second = virtualThreads.submit(() -> loader.get().load("m1"));
        waitUntil(() -> joins.get() == 2);
        joined.countDown();
        for (Future<String> caller : List.of(first, second)) {
            assertThatThrownBy(() -> caller.get(5, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(ApiException.class)
                    .hasMessage("match m1: no fetch could start");
        }
        assertThat(loader.get().inFlight()).isZero();
    }

    @Test
    void aFetchThatOverranAndFinishesLateLeavesTheNewerFetchAlone() throws Exception {
        var fetches = new AtomicInteger();
        var firstGoes = new CountDownLatch(1);
        var firstDone = new CountDownLatch(1);
        var secondGoes = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        await(firstGoes);
                        firstDone.countDown();
                        return "late";
                    }
                    await(secondGoes);
                    return "newer";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        var joins = countJoins(loader);
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        Future<String> waiting = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> joins.get() == 2 && fetches.get() == 2);

        firstGoes.countDown();
        await(firstDone);
        Thread.sleep(100);
        assertThat(loader.inFlight())
                .as("the late fetch left the newer one in place")
                .isEqualTo(1);
        Future<String> joining = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> joins.get() == 3);
        assertThat(fetches).as("the third caller joined the newer fetch").hasValue(2);

        secondGoes.countDown();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("newer");
        assertThat(joining.get(5, TimeUnit.SECONDS)).isEqualTo("newer");
    }

    @Test
    void aFetchThatOverranDoesNotWriteOverTheNewerFetchsResult() throws Exception {
        Field<String> status = Field.shared("status");
        var summary = new Endpoint("summary", Set.of(status), Set.of(status));
        var cache = new EntityCache<String>("match", 100, Duration.ofHours(1), DEADLINE.plus(MARGIN));
        var fetches = new AtomicInteger();
        var firstGoes = new CountDownLatch(1);
        var firstWrote = new AtomicReference<Boolean>();
        var loader = new Loader<String, String>(
                "match",
                (key, _, abandoned) -> {
                    var stamp = cache.stamp(key, abandoned);
                    if (fetches.incrementAndGet() == 1) {
                        await(firstGoes);
                        firstWrote.set(cache.writeAuthoritative(
                                key, Write.from(summary, Locale.ENGLISH).put(status, "not started"), stamp));
                        return "late";
                    }
                    cache.writeAuthoritative(
                            key, Write.from(summary, Locale.ENGLISH).put(status, "live"), stamp);
                    return "newer";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system());
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        assertThat(loader.load("m1")).isEqualTo("newer");

        firstGoes.countDown();
        waitUntil(() -> firstWrote.get() != null);
        assertThat(firstWrote.get()).as("the abandoned fetch's write").isFalse();
        assertThat(java.util.Objects.requireNonNull(cache.get("m1")).get(status, null))
                .isEqualTo("live");
    }

    @Test
    void aFetchThatFinishesAtOnceIsNotLeftBehind() {
        // the fetch can finish before the caller looks at it; it must still leave the map
        var loader = new Loader<Integer, Integer>(
                "match", (key, _, _) -> key * 2, DEADLINE, MARGIN, virtualThreads, FeedClock.system());
        for (int i = 0; i < 1_000; i++) {
            assertThat(loader.load(i % 7)).isEqualTo((i % 7) * 2);
        }
        waitUntil(() -> loader.inFlight() == 0);
    }

    /**
     * Two loads that wait for each other - a match for its competitor, the competitor for the match -
     * each started by its own caller at the same moment. Neither can finish; both end at their
     * deadline instead of hanging, and afterwards the keys load again.
     */
    @Test
    void loadsThatWaitForEachOtherEndAtTheirDeadlineInsteadOfHanging() throws Exception {
        var bothFetching = new CountDownLatch(2);
        var matches = new AtomicReference<Loader<String, String>>();
        var competitors = new AtomicReference<Loader<String, String>>();
        var cycle = new java.util.concurrent.atomic.AtomicBoolean(true);
        matches.set(new Loader<>(
                "match",
                (key, _, _) -> {
                    if (cycle.get()) {
                        bothFetching.countDown();
                        await(bothFetching);
                        return "match with " + competitors.get().load("c1");
                    }
                    return "match";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system()));
        competitors.set(new Loader<>(
                "competitor",
                (key, _, _) -> {
                    if (cycle.get()) {
                        bothFetching.countDown();
                        await(bothFetching);
                        return "competitor of " + matches.get().load("m1");
                    }
                    return "competitor";
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                FeedClock.system()));

        long started = System.nanoTime();
        Future<String> match = virtualThreads.submit(() -> matches.get().load("m1"));
        Future<String> competitor =
                virtualThreads.submit(() -> competitors.get().load("c1"));
        assertThatThrownBy(() -> match.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        assertThatThrownBy(() -> competitor.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("bounded by the deadlines, not a hang")
                .isLessThan(Duration.ofSeconds(3));

        cycle.set(false);
        waitUntil(() -> matches.get().inFlight() == 0 && competitors.get().inFlight() == 0);
        assertThat(matches.get().load("m1")).isEqualTo("match");
        assertThat(competitors.get().load("c1")).isEqualTo("competitor");
    }

    /** A fetch that fails for the keys in {@code failing} and counts its requests. */
    private record Flaky(Loader<String, String> loader, AtomicInteger fetches, Set<String> failing) {
        void failsFor(String... keys) {
            failing.clear();
            failing.addAll(List.of(keys));
        }
    }

    private Flaky flaky(SplitClock split) {
        var fetches = new AtomicInteger();
        Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    if (failing.contains(key)) {
                        throw new ApiException("Failed to get data: 503");
                    }
                    return "value of " + key;
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                split.clock());
        return new Flaky(loader, fetches, failing);
    }

    private static void failsBackingOff(Loader<String, String> loader, String key) {
        assertThatThrownBy(() -> loader.load(key))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("backing off")
                .hasRootCauseMessage("Failed to get data: 503");
    }

    @Test
    void aKeyWhoseFetchFailedBacksOffAndItsReadsMakeNoRequest() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Failed to get data: 503");
        for (int read = 0; read < 4; read++) {
            failsBackingOff(flaky.loader(), "m1");
        }
        assertThat(flaky.fetches()).as("the four reads asked for nothing").hasValue(1);

        split.advanceMono(Loader.FIRST_BACKOFF.minusMillis(1));
        failsBackingOff(flaky.loader(), "m1");
        assertThat(flaky.fetches()).hasValue(1);
        split.advanceMono(Duration.ofMillis(1));
        assertThatThrownBy(() -> flaky.loader().load("m1")).hasMessage("Failed to get data: 503");
        assertThat(flaky.fetches()).as("after the backoff, one more").hasValue(2);
    }

    @Test
    void theBackoffMessageSaysWhenTheNextFetchMayStartByTheWallClockAndCountsOnTheMonotonicOne() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> flaky.loader().load("m1"))
                .hasMessageContaining("backing off, not fetched again before 2026-10-15T12:00:01Z, 1 fetches");
        // the wall clock stepping a day on does not end it, nor does stepping it back extend it
        split.stepWall(Duration.ofDays(1));
        failsBackingOff(flaky.loader(), "m1");
        split.stepWall(Duration.ofDays(-2));
        split.advanceMono(Loader.FIRST_BACKOFF);
        assertThatThrownBy(() -> flaky.loader().load("m1")).hasMessage("Failed to get data: 503");
        assertThat(flaky.fetches()).hasValue(2);
    }

    @Test
    void theBackoffDoublesWithEachFailureInARowUpToItsCap() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        long fetches = 0;
        for (int failure = 1; failure <= 9; failure++) {
            Duration backoff = Duration.ofSeconds(1L << (failure - 1)).compareTo(Loader.LONGEST_BACKOFF) < 0
                    ? Duration.ofSeconds(1L << (failure - 1))
                    : Loader.LONGEST_BACKOFF;
            assertThatThrownBy(() -> flaky.loader().load("m1")).hasMessage("Failed to get data: 503");
            fetches++;
            split.advanceMono(backoff.minusMillis(1));
            failsBackingOff(flaky.loader(), "m1");
            assertThat(flaky.fetches())
                    .as("failure %d backs off %s", failure, backoff)
                    .hasValue((int) fetches);
            split.advanceMono(Duration.ofMillis(1));
        }
    }

    @Test
    void aSuccessEndsTheBackoffAndTheNextFailureStartsOverAtTheFirst() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        for (int failure = 0; failure < 3; failure++) {
            assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
            split.advanceMono(Duration.ofSeconds(1L << failure));
        }
        flaky.failsFor();
        assertThat(flaky.loader().load("m1")).isEqualTo("value of m1");
        assertThat(flaky.loader().failing()).as("nothing remembered").isZero();

        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1")).hasMessage("Failed to get data: 503");
        split.advanceMono(Loader.FIRST_BACKOFF);
        assertThatThrownBy(() -> flaky.loader().load("m1"))
                .as("the first backoff again, not the fourth's 8 s")
                .hasMessage("Failed to get data: 503");
        assertThat(flaky.fetches())
                .as("three failures, the success, two failures")
                .hasValue(6);
    }

    @Test
    void aKeyThatBacksOffLeavesTheOthersAlone() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
        assertThat(flaky.loader().load("m2")).isEqualTo("value of m2");
        failsBackingOff(flaky.loader(), "m1");
        assertThat(flaky.loader().load("m2")).isEqualTo("value of m2");
        assertThat(flaky.fetches()).as("m1 once, m2 twice").hasValue(3);
    }

    @Test
    void forgettingAKeyEndsItsBackoffAndOnlyIts() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1", "m2");
        for (String key : List.of("m1", "m2")) {
            assertThatThrownBy(() -> flaky.loader().load(key)).isInstanceOf(ApiException.class);
        }
        flaky.loader().forget("m1"::equals);
        flaky.failsFor();
        assertThat(flaky.loader().load("m1")).isEqualTo("value of m1");
        failsBackingOff(flaky.loader(), "m2");
        assertThat(flaky.fetches()).hasValue(3);

        flaky.loader().forgetAll();
        assertThat(flaky.loader().load("m2")).isEqualTo("value of m2");
        assertThat(flaky.fetches()).hasValue(4);
    }

    @Test
    void aFailureOfAFetchForgottenWhileItRanBacksNothingOff() throws Exception {
        var split = new SplitClock();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        started.countDown();
                        await(release);
                        throw new ApiException("Failed to get data: 503");
                    }
                    return "value of " + key;
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                split.clock());
        Future<String> first = virtualThreads.submit(() -> loader.load("m1"));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            loader.forget("m1"::equals);
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        assertThat(loader.load("m1"))
                .as("the clear asked for a fresh load: the failure from before it is no reason to wait")
                .isEqualTo("value of m1");
    }

    @Test
    void aFetchThatFailsAfterAnotherReplacedItBacksNothingOff() throws Exception {
        var split = new SplitClock();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        started.countDown();
                        await(release);
                        throw new ApiException("Failed to get data: 503");
                    }
                    return "value of " + key;
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                split.clock());
        Future<String> stuck = virtualThreads.submit(() -> loader.load("m1"));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            split.advanceMono(DEADLINE.plus(MARGIN).plusMillis(1));
            assertThat(loader.load("m1")).as("replaces the stuck one").isEqualTo("value of m1");
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> stuck.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        assertThat(loader.failing()).as("the newer fetch owns the key").isZero();
        assertThat(loader.load("m1")).isEqualTo("value of m1");
    }

    @Test
    void aSuccessOfAFetchThatWasReplacedLeavesTheNewerFailureStanding() throws Exception {
        var split = new SplitClock();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        started.countDown();
                        await(release);
                        return "late value";
                    }
                    throw new ApiException("Failed to get data: 503");
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                split.clock());
        Future<String> stuck = virtualThreads.submit(() -> loader.load("m1"));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            split.advanceMono(DEADLINE.plus(MARGIN).plusMillis(1));
            assertThatThrownBy(() -> loader.load("m1")).hasMessage("Failed to get data: 503");
        } finally {
            release.countDown();
        }
        settledLate(stuck);
        failsBackingOff(loader, "m1");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void aFailureNobodyAskedAgainAfterIsForgottenAndTheNextOneStartsOver() {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
        split.advanceMono(Loader.FIRST_BACKOFF);
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
        assertThat(flaky.loader().failing()).isEqualTo(1);

        split.advanceMono(Loader.LONGEST_BACKOFF.multipliedBy(2));
        assertThat(flaky.loader().failing()).as("nobody tried again: gone").isZero();
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);
        split.advanceMono(Loader.FIRST_BACKOFF);
        assertThatThrownBy(() -> flaky.loader().load("m1"))
                .as("counted from one again: 1 s, not the 4 s a third failure would back off")
                .hasMessage("Failed to get data: 503");
    }

    @Test
    void theFailuresRememberedAreBounded() {
        var split = new SplitClock();
        var flaky = flaky(split);
        var keys = new ArrayList<String>();
        for (int key = 0; key < Loader.MAXIMUM_FAILURES + 500; key++) {
            keys.add("m" + key);
        }
        flaky.failsFor(keys.toArray(String[]::new));
        for (String key : keys) {
            assertThatThrownBy(() -> flaky.loader().load(key)).isInstanceOf(ApiException.class);
        }
        assertThat(flaky.loader().failing()).isLessThanOrEqualTo(Loader.MAXIMUM_FAILURES);
    }

    @Test
    void anErrorFromAFetchBacksNothingOff() {
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    throw new ExceptionInInitializerError("the binding did not load");
                },
                DEADLINE,
                MARGIN,
                virtualThreads,
                new SplitClock().clock());
        for (int read = 0; read < 2; read++) {
            assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ExceptionInInitializerError.class);
        }
        assertThat(fetches).hasValue(2);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void readersOfAFailingKeyAllFailAtOnceAndMakeNoRequest() throws Exception {
        var split = new SplitClock();
        var flaky = flaky(split);
        flaky.failsFor("m1");
        assertThatThrownBy(() -> flaky.loader().load("m1")).isInstanceOf(ApiException.class);

        int readers = 20;
        var start = new CountDownLatch(1);
        List<Future<Boolean>> reads = new ArrayList<>();
        for (int reader = 0; reader < readers; reader++) {
            reads.add(virtualThreads.submit(() -> {
                await(start);
                try {
                    flaky.loader().load("m1");
                    return false;
                } catch (ApiException failed) {
                    return String.valueOf(failed.getMessage()).contains("backing off");
                }
            }));
        }
        start.countDown();
        for (Future<Boolean> read : reads) {
            assertThat(read.get(30, TimeUnit.SECONDS))
                    .as("failed at once, backing off")
                    .isTrue();
        }
        assertThat(flaky.fetches())
                .as("the first failure's one request, and no more")
                .hasValue(1);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void concurrentFirstReadersOfAFailingKeyShareOneRequestAndTheLateOnesMakeNone() throws Exception {
        var split = new SplitClock();
        var release = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    await(release);
                    throw new ApiException("Failed to get data: 503");
                },
                Duration.ofSeconds(5),
                MARGIN,
                virtualThreads,
                split.clock());
        var joins = countJoins(loader);
        int readers = 20;
        List<Future<String>> reads = new ArrayList<>();
        try {
            for (int reader = 0; reader < readers; reader++) {
                reads.add(virtualThreads.submit(() -> loader.load("m1")));
            }
            waitUntil(() -> joins.get() == readers);
        } finally {
            release.countDown();
        }
        for (Future<String> read : reads) {
            assertThatThrownBy(() -> read.get(30, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        }
        for (int late = 0; late < readers; late++) {
            failsBackingOff(loader, "m1");
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aReaderThatCheckedBeforeTheFailureAndJoinsAfterItMakesNoRequestEither() throws Exception {
        var split = new SplitClock();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var checked = new CountDownLatch(1);
        var late = new CountDownLatch(1);
        var fetches = new AtomicInteger();
        var loader = Loader.<String, String>withBackoff(
                "match",
                (key, _, _) -> {
                    fetches.incrementAndGet();
                    started.countDown();
                    await(release);
                    throw new ApiException("Failed to get data: 503");
                },
                Duration.ofSeconds(5),
                MARGIN,
                virtualThreads,
                split.clock());
        Future<String> first = virtualThreads.submit(() -> loader.load("m1"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // the second finds the key not backing off, and waits before it joins or starts a flight
        loader.checked = key -> {
            checked.countDown();
            await(late);
        };
        Future<String> second = virtualThreads.submit(() -> loader.load("m1"));
        try {
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
            waitUntil(() -> loader.inFlight() == 0);
        } finally {
            late.countDown();
        }
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("backing off");
        assertThat(fetches).as("its own flight started no request").hasValue(1);
    }

    /** A caller that was left behind either got its value or failed: both are what a late finish may do. */
    private static void settledLate(Future<String> caller) throws Exception {
        try {
            assertThat(caller.get(5, TimeUnit.SECONDS)).isEqualTo("late value");
        } catch (java.util.concurrent.ExecutionException failed) {
            assertThat(failed.getCause()).isInstanceOf(ApiException.class);
        }
    }

    /** A fetch that waits to be released, and says what it was given. */
    private record Held(
            Loader<String, String> loader,
            AtomicInteger fetches,
            AtomicReference<Deadline> deadline,
            AtomicReference<BooleanSupplier> abandoned,
            CountDownLatch started,
            CountDownLatch release) {}

    private Held held(SplitClock split) {
        var fetches = new AtomicInteger();
        var deadline = new AtomicReference<Deadline>();
        var abandoned = new AtomicReference<BooleanSupplier>();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, given, gone) -> {
                    if (fetches.incrementAndGet() == 1) {
                        deadline.set(given);
                        abandoned.set(gone);
                        started.countDown();
                    }
                    await(release);
                    return "value of " + key;
                },
                Duration.ofSeconds(5),
                MARGIN,
                virtualThreads,
                split.clock());
        return new Held(loader, fetches, deadline, abandoned, started, release);
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aFetchsDeadlineAndItsAbandonmentAreNotMovedByAStepOfTheWallClock(long minutes) throws Exception {
        var split = new SplitClock();
        var fetch = held(split);
        Future<String> caller = virtualThreads.submit(() -> fetch.loader().load("m1"));
        try {
            assertThat(fetch.started().await(5, TimeUnit.SECONDS)).isTrue();
            split.stepWall(Duration.ofMinutes(minutes));
            assertThat(fetch.deadline().get().remaining()).isEqualTo(Duration.ofSeconds(5));
            assertThat(fetch.deadline().get().passed()).isFalse();
            assertThat(fetch.abandoned().get().getAsBoolean()).isFalse();
        } finally {
            fetch.release().countDown();
        }
        assertThat(caller.get(5, TimeUnit.SECONDS)).isEqualTo("value of m1");
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 0, -30})
    void aFetchsDeadlinePassesAndItIsAbandonedWithTheMonotonicClockWhateverTheWallClockSays(long minutes)
            throws Exception {
        var split = new SplitClock();
        var fetch = held(split);
        Future<String> caller = virtualThreads.submit(() -> fetch.loader().load("m1"));
        try {
            assertThat(fetch.started().await(5, TimeUnit.SECONDS)).isTrue();
            split.advanceMono(Duration.ofSeconds(5));
            split.stepWall(Duration.ofMinutes(minutes));
            assertThat(fetch.deadline().get().passed()).isTrue();
            assertThat(fetch.deadline().get().remaining()).isEqualTo(Duration.ZERO);
            assertThat(fetch.abandoned().get().getAsBoolean())
                    .as("callers wait a margin more")
                    .isFalse();
            split.advanceMono(MARGIN.plusMillis(1));
            assertThat(fetch.abandoned().get().getAsBoolean()).isTrue();
        } finally {
            fetch.release().countDown();
        }
        settled(caller);
    }

    @Test
    void aCallersDeadlineOnAnotherClockIsKeptByWhatIsLeftOfIt() throws Exception {
        var split = new SplitClock();
        var fetch = held(split);
        // a deadline another clock counts, with a reading hours away from this loader's
        var elsewhere = new SplitClock();
        elsewhere.advanceMono(Duration.ofHours(5));
        var within = Deadline.in(Duration.ofMillis(100), elsewhere.clock()::monoNanos);
        Future<String> first = virtualThreads.submit(() -> fetch.loader().load("m1"));
        try {
            assertThat(fetch.started().await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> bounded = virtualThreads.submit(() -> fetch.loader().load("m1", within));
            assertThatThrownBy(() -> bounded.get(3, TimeUnit.SECONDS))
                    .as("a hundred milliseconds left, not the flight's five seconds")
                    .hasCauseInstanceOf(ApiException.class)
                    .hasMessageContaining("its caller's deadline");
        } finally {
            fetch.release().countDown();
        }
        settled(first);
    }

    @Test
    void aCallerJoinsTheRunningFetchUntilItsTimeIsOutOnTheMonotonicClock() throws Exception {
        var split = new SplitClock();
        var fetch = held(split);
        var joins = countJoins(fetch.loader());
        Future<String> first = virtualThreads.submit(() -> fetch.loader().load("m1"));
        try {
            assertThat(fetch.started().await(5, TimeUnit.SECONDS)).isTrue();
            split.stepWall(Duration.ofMinutes(30));
            Future<String> second = virtualThreads.submit(() -> fetch.loader().load("m1"));
            waitUntil(() -> joins.get() == 2);
            assertThat(fetch.abandoned().get().getAsBoolean())
                    .as("a step of the wall clock does not end the flight: the caller joined it")
                    .isFalse();
            assertThat(fetch.fetches()).hasValue(1);

            split.advanceMono(Duration.ofSeconds(5).plus(MARGIN).plusMillis(1));
            split.stepWall(Duration.ofMinutes(-30));
            Future<String> third = virtualThreads.submit(() -> fetch.loader().load("m1"));
            waitUntil(() -> fetch.fetches().get() == 2);
            assertThat(fetch.abandoned().get().getAsBoolean())
                    .as("past its callers' time, it is abandoned and replaced")
                    .isTrue();
            fetch.release().countDown();
            settled(first);
            settled(second);
            assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo("value of m1");
        } finally {
            fetch.release().countDown();
        }
    }

    /**
     * Waits for a caller to be done: it got the value, or ran out of time. A caller that has not yet
     * worked out how long it waits when the clock is moved past its time fails at once, which a test
     * moving the clock cannot tell from one that failed a moment later.
     */
    private static void settled(Future<String> caller) throws Exception {
        try {
            assertThat(caller.get(5, TimeUnit.SECONDS)).isEqualTo("value of m1");
        } catch (java.util.concurrent.ExecutionException timedOut) {
            assertThat(timedOut.getCause()).isInstanceOf(ApiException.class);
        }
    }

    /** Counts the callers that join or start a flight. */
    private static <K> AtomicInteger countJoins(Loader<K, ?> loader) {
        var joins = new AtomicInteger();
        loader.joined = _ -> joins.incrementAndGet();
        return joins;
    }

    /** Loads, expecting the load to fail, and records when it failed. */
    private static void failsAt(Loader<String, String> loader, String caller, java.util.Map<String, Long> failedAt) {
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        failedAt.put(caller, System.nanoTime());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not within 5 s");
            }
            Thread.onSpinWait();
        }
    }
}
