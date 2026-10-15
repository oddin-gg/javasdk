package com.oddin.oddsfeedsdk.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.clock.SplitClock;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The side-load workers follow the REST concurrency limit: half of it, one at least, 16 at most. */
class FeedCoreTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 1", "3, 1", "4, 2", "16, 8", "32, 16", "40, 16", "1000000, 16"})
    void sideLoadWorkersAreHalfTheLimitFromOneToSixteen(int limit, int workers) {
        assertThat(FeedCore.sideLoadWorkers(limit)).isEqualTo(workers);
    }

    /**
     * The summaries a preload loads are held in the fake, and counted as they arrive: as many as
     * there are workers, and no more while those are held. The window to see no more is bounded, a
     * further worker would arrive within it by far.
     */
    @ParameterizedTest
    @CsvSource({"16, 8", "2, 1"})
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void thePreloadHasAsManyLoadsInFlightAsHalfTheLimit(int limit, int workers) throws Exception {
        var inFlight = new AtomicInteger();
        var most = new AtomicInteger();
        var arrived = new CountDownLatch(workers);
        var tooMany = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var api = FakeRestServer.start()) {
            api.respondWith(request -> {
                if (!request.path().endsWith("/summary")) {
                    return null;
                }
                int now = inFlight.incrementAndGet();
                most.accumulateAndGet(now, Math::max);
                arrived.countDown();
                if (now > workers) {
                    tooMany.countDown();
                }
                try {
                    release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return null;
            });
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .setRestConcurrencyLimit(limit)
                    .build();
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                for (int i = 0; i < 3 * workers; i++) {
                    core.matches().preload(URN.parse("od:match:" + (1000 + i)), List.of(Locale.ENGLISH));
                }

                assertThat(arrived.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                        .as("the summaries in flight, one for each of the %d workers", workers)
                        .isTrue();
                assertThat(tooMany.await(1, TimeUnit.SECONDS))
                        .as("a summary more than the workers' %d in flight", workers)
                        .isFalse();
            } finally {
                release.countDown();
                core.close();
            }
            assertThat(most.get()).as("most summaries in flight at once").isEqualTo(workers);
        }
    }

    /**
     * The clock a feed starts its core with is the one the catalogs, the live state and the side-loads
     * count by: moved on its monotonic side only, each of them sees the time pass, and moved on its
     * wall side only, none of them does.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theCoreIsBuiltOnTheFeedsClock() throws Exception {
        var split = new SplitClock();
        var match = URN.parse("od:match:198314");
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(
                    configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {}, split.clock());
            try {
                core.voidReasons().all();
                var live = new OFSportEventStatus();
                live.setStatus(OFEventStatus.LIVE);
                core.matches()
                        .oddsChange(
                                match, 1, 1_000, Duration.ZERO, split.clock().monoMillis(), live);

                split.stepWall(Duration.ofHours(3));
                core.voidReasons().all();
                assertThat(core.catalogs())
                        .as("the void reasons, three hours on by the wall clock only")
                        .allSatisfy(catalog -> assertThat(catalog.servedStale()).isZero());
                assertThat(core.matches().live(match))
                        .as("the feed wrote a moment ago by the monotonic clock: no summary is loaded")
                        .isNotNull();
                assertThat(api.requests("GET", "/v1/sports/en/sport_events/od:match:198314/summary"))
                        .isEmpty();
                var deadline = new CompletableFuture<Deadline>();
                core.sideLoads().offer(deadline::complete);
                assertThat(deadline.get(10, TimeUnit.SECONDS).remaining())
                        .isEqualTo(configuration.getHttpClientTimeout());

                split.advanceMono(Duration.ofHours(3));
                split.stepWall(Duration.ofHours(-3));
                core.voidReasons().all();
                assertThat(core.catalogs())
                        .as("three hours on by the monotonic clock: the void reasons are served stale")
                        .anySatisfy(catalog -> assertThat(catalog.servedStale()).isEqualTo(1));
            } finally {
                core.close();
            }
        }
    }

    /**
     * A warm-up the side-loads run reaches the profile loaders with the deadline the side-load was
     * given, which counts on the feed's clock: with that clock's reading hours from any other, the
     * loader keeps to what is left of it. A fetch that never answers is waited for 50 milliseconds,
     * not the loader's own timeout and not none at all.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aSideLoadIsWaitedForByTheTimeItHasLeftWhateverTheClocksReading() throws Exception {
        var split = new SplitClock();
        split.advanceMono(Duration.ofHours(5));
        var release = new CountDownLatch(1);
        var failure = new CompletableFuture<Throwable>();
        try (var api = FakeRestServer.start()) {
            api.respondWith(request -> {
                if (request.path().contains("/competitors/")) {
                    await(release);
                }
                return null;
            });
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(
                    configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {}, split.clock());
            try {
                core.sideLoads().offer(deadline -> {
                    split.advanceMono(configuration.getHttpClientTimeout().minusMillis(50));
                    try {
                        core.profiles().competitor(URN.parse("od:competitor:47214"), Locale.ENGLISH, deadline);
                        failure.complete(new AssertionError("loaded"));
                    } catch (RuntimeException e) {
                        failure.complete(e);
                    }
                });
                assertThat(failure.get(5, TimeUnit.SECONDS))
                        .as("given up at the side-load's deadline, which is 50 ms off")
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("its caller's deadline");
            } finally {
                release.countDown();
                core.close();
            }
        }
    }

    /**
     * Every loader of the core ends a flight past its callers' time by the feed's clock: with that
     * clock moved on, a second read starts a fetch of its own instead of joining the first, which a
     * loader counting by a clock of its own would not see run out.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("loaderReads")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void everyLoaderOfTheCoreEndsItsFlightsByTheFeedsClock(String name, Predicate<String> path, Consumer<FeedCore> read)
            throws Exception {
        var split = new SplitClock();
        var requests = new AtomicInteger();
        var release = new CountDownLatch(1);
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try (var api = FakeRestServer.start()) {
            api.respondWith(request -> {
                if (path.test(request.path())) {
                    requests.incrementAndGet();
                    await(release);
                }
                return null;
            });
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(
                    configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {}, split.clock());
            try {
                readers.execute(() -> read.accept(core));
                waitFor(() -> requests.get() == 1, name + ": the first fetch");

                split.advanceMono(Duration.ofMinutes(1));
                readers.execute(() -> read.accept(core));
                waitFor(() -> requests.get() == 2, name + ": a fetch of the second read's own");
            } finally {
                release.countDown();
                readers.shutdownNow();
                core.close();
            }
        }
    }

    private static Stream<Arguments> loaderReads() {
        var match = URN.parse("od:match:198314");
        return Stream.of(
                loaderRead(
                        "match summary",
                        p -> p.endsWith("/summary"),
                        core -> core.matches().match(match, Locale.ENGLISH)),
                loaderRead(
                        "match fixture",
                        p -> p.endsWith("/fixture"),
                        core -> core.matches().fixture(match)),
                loaderRead(
                        "competitor",
                        p -> p.contains("/competitors/"),
                        core -> core.profiles().competitor(URN.parse("od:competitor:47214"), Locale.ENGLISH, null)),
                loaderRead(
                        "player",
                        p -> p.contains("/players/"),
                        core -> core.profiles().player(URN.parse("od:player:9001"), Locale.ENGLISH, null)),
                loaderRead(
                        "tournament",
                        p -> p.endsWith("/info"),
                        core -> core.profiles().tournament(URN.parse("od:tournament:1042"), Locale.ENGLISH, null)),
                loaderRead(
                        "sport tournaments",
                        p -> p.endsWith("/tournaments"),
                        core -> core.profiles().sportTournaments(URN.parse("od:sport:1"), Locale.ENGLISH, null)),
                loaderRead(
                        "sports",
                        p -> p.endsWith("/en/sports"),
                        core -> core.profiles().sports(Locale.ENGLISH, null)),
                loaderRead(
                        "void reasons",
                        p -> p.endsWith("/void_reasons"),
                        core -> core.voidReasons().all()),
                loaderRead(
                        "match statuses",
                        p -> p.endsWith("/match_status"),
                        core -> core.statuses().description(1, Locale.ENGLISH)),
                loaderRead(
                        "market descriptions",
                        p -> p.endsWith("/en/markets"),
                        core -> core.markets().market(1, null, Locale.ENGLISH)));
    }

    private static Arguments loaderRead(String name, Predicate<String> path, Consumer<FeedCore> read) {
        return Arguments.of(name, path, read);
    }

    private static void waitFor(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("not within " + WAIT + ": " + what);
            }
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Quiet implements GlobalEventsListener {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
