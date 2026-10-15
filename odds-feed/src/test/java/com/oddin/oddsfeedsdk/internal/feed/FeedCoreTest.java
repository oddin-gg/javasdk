package com.oddin.oddsfeedsdk.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    private static final class Quiet implements GlobalEventsListener {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
