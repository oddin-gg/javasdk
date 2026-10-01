package com.oddin.oddsfeedsdk.internal.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache;
import com.oddin.oddsfeedsdk.internal.cache.Field;
import com.oddin.oddsfeedsdk.internal.cache.Write;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
                (key, _) -> {
                    fetches.incrementAndGet();
                    await(release);
                    return "value of " + key;
                },
                Duration.ofSeconds(5),
                MARGIN,
                virtualThreads);

        List<Future<String>> callers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            callers.add(virtualThreads.submit(() -> loader.load("m1")));
        }
        waitUntil(() -> loader.waiting("m1") == 20);
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
                (key, _) -> {
                    fetches.incrementAndGet();
                    await(never);
                    return "late";
                },
                DEADLINE,
                MARGIN,
                virtualThreads);

        long started = System.nanoTime();
        Future<String> first = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> loader.waiting("m1") == 1);
        Future<String> second = virtualThreads.submit(() -> loader.load("m1"));
        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(ApiException.class)
                .hasMessage("match m1 not loaded within 700 ms");
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
                (key, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        await(never);
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads);
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        assertThat(loader.load("m1"))
                .as("a fresh fetch, not the one still stuck")
                .isEqualTo("value");
        assertThat(fetches).hasValue(2);
        never.countDown();
    }

    @Test
    void manyWaitersThatRunOutOfTimeTogetherLetGoOfTheFetch() throws Exception {
        var fetches = new AtomicInteger();
        var never = new CountDownLatch(1);
        var loader = new Loader<String, String>(
                "match",
                (key, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        await(never);
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads);
        var waiters = new java.util.ArrayList<Future<String>>();
        for (int i = 0; i < 20; i++) {
            waiters.add(virtualThreads.submit(() -> loader.load("m1")));
        }
        for (Future<String> waiter : waiters) {
            assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS)).cause().isInstanceOf(ApiException.class);
        }
        assertThat(loader.inFlight()).as("the last to give up let go").isZero();
        assertThat(loader.load("m1")).as("a fresh fetch").isEqualTo("value");
        assertThat(fetches).hasValue(2);
        never.countDown();
    }

    @Test
    void aFailedFetchFailsEveryWaiterAndTheNextMissStartsAfresh() {
        var fetches = new AtomicInteger();
        var loader = new Loader<String, String>(
                "match",
                (key, _) -> {
                    if (fetches.incrementAndGet() == 1) {
                        throw new ApiException("Failed to get data: 503");
                    }
                    return "value";
                },
                DEADLINE,
                MARGIN,
                virtualThreads);
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("503");
        assertThat(loader.load("m1")).isEqualTo("value");
    }

    @Test
    void anErrorFromAFetchReachesTheCallerAsItWas() {
        var loader = new Loader<String, String>(
                "match",
                (key, _) -> {
                    throw new ExceptionInInitializerError("the binding did not load");
                },
                DEADLINE,
                MARGIN,
                virtualThreads);
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ExceptionInInitializerError.class)
                .hasMessage("the binding did not load");
    }

    @Test
    void aFetchThatCannotStartLeavesNothingBehind() {
        var loader = new Loader<String, String>("match", (key, _) -> "value", DEADLINE, MARGIN, task -> {
            throw new java.util.concurrent.RejectedExecutionException("closed");
        });
        assertThatThrownBy(() -> loader.load("m1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("match m1: no fetch could start");
        assertThat(loader.inFlight()).isZero();
    }

    @Test
    void aCallerThatJoinedAFetchThatCannotStartGetsTheSameFailure() throws Exception {
        var joined = new CountDownLatch(1);
        var loader = new AtomicReference<Loader<String, String>>();
        loader.set(new Loader<>("match", (key, _) -> "value", DEADLINE, MARGIN, task -> {
            await(joined);
            throw new java.util.concurrent.RejectedExecutionException("closed");
        }));
        Future<String> first = virtualThreads.submit(() -> loader.get().load("m1"));
        waitUntil(() -> loader.get().waiting("m1") == 1);
        Future<String> second = virtualThreads.submit(() -> loader.get().load("m1"));
        waitUntil(() -> loader.get().waiting("m1") == 2);
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
                (key, _) -> {
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
                virtualThreads);
        assertThatThrownBy(() -> loader.load("m1")).isInstanceOf(ApiException.class);
        Future<String> waiting = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> loader.waiting("m1") == 1);

        firstGoes.countDown();
        await(firstDone);
        Thread.sleep(100);
        assertThat(loader.waiting("m1"))
                .as("the late fetch left the newer one in place")
                .isEqualTo(1);
        Future<String> joining = virtualThreads.submit(() -> loader.load("m1"));
        waitUntil(() -> loader.waiting("m1") == 2);
        assertThat(fetches).as("the third caller joined the newer fetch").hasValue(2);

        secondGoes.countDown();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("newer");
        assertThat(joining.get(5, TimeUnit.SECONDS)).isEqualTo("newer");
    }

    @Test
    void aFetchThatOverranDoesNotWriteOverTheNewerFetchsResult() throws Exception {
        Field<String> status = Field.shared("status");
        var summary = new Endpoint("summary", Set.of(status), Set.of(status));
        var cache = new EntityCache<String>("match", 100, Duration.ofHours(1));
        var fetches = new AtomicInteger();
        var firstGoes = new CountDownLatch(1);
        var firstWrote = new AtomicReference<Boolean>();
        var loader = new Loader<String, String>(
                "match",
                (key, abandoned) -> {
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
                virtualThreads);
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
        var loader = new Loader<Integer, Integer>("match", (key, _) -> key * 2, DEADLINE, MARGIN, virtualThreads);
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
                (key, _) -> {
                    if (cycle.get()) {
                        bothFetching.countDown();
                        await(bothFetching);
                        return "match with " + competitors.get().load("c1");
                    }
                    return "match";
                },
                DEADLINE,
                MARGIN,
                virtualThreads));
        competitors.set(new Loader<>(
                "competitor",
                (key, _) -> {
                    if (cycle.get()) {
                        bothFetching.countDown();
                        await(bothFetching);
                        return "competitor of " + matches.get().load("m1");
                    }
                    return "competitor";
                },
                DEADLINE,
                MARGIN,
                virtualThreads));

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
