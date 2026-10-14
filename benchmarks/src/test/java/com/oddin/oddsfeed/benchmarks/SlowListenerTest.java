package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;

/**
 * A client whose listener is slow slows its own session and no other. Two sessions on one feed, a
 * live-only one whose listener sleeps for every message and a prematch-only one whose listener does
 * nothing, get the same traffic: the slow session falls behind by seconds - its queue fills to the
 * prefetch and the rest waits at the broker - while the other session's messages wait in the SDK no
 * longer than they do when nothing is slow, and its queue stays as empty.
 *
 * <p>The wait is the time from the broker client taking a message to the session's callback starting,
 * which the SDK stamps on every message; the queue is the session's depth from the feed's health. The
 * budgets are in {@code listener-budgets.properties}. The fake feed is a RabbitMQ container, so this
 * needs Docker, and skips without it unless {@code CI} is set.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SlowListenerTest {

    /** What a message of the slow session costs its listener. */
    private static final Duration SLOW_BY = Duration.ofMillis(50);

    /**
     * Messages for each session. The fake feed publishes about one in 4 ms, so the 600 messages take
     * several seconds, and the slow session, at {@link #SLOW_BY} each, needs three times as long: its
     * queue fills to about the prefetch of 200, and what is left waits at the broker.
     */
    private static final int MESSAGES = 300;

    private static @Nullable FakeFeed broker;

    @BeforeAll
    static void startBroker() {
        boolean docker = DockerClientFactory.instance().isDockerAvailable();
        if (System.getenv("CI") != null) {
            assertThat(docker)
                    .as("Docker, which the fake feed needs, and CI is set")
                    .isTrue();
        } else {
            assumeTrue(docker, "no Docker here: the fake feed is a RabbitMQ container");
        }
        broker = FakeFeed.start();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void aSlowListenerSlowsOnlyItsOwnSession() throws Exception {
        var baseline = run(Duration.ZERO);
        var slowed = run(SLOW_BY);
        System.out.printf(
                "other session's wait (ms), nothing slow: p99 %d, max %d, queue peak %d%n"
                        + "other session's wait (ms), one slow: p99 %d, max %d, queue peak %d%n"
                        + "the slow session: wait p99 %d ms, max %d ms, queue peak %d, handled in %d ms%n",
                baseline.fast.p99(),
                baseline.fast.max(),
                baseline.fast.queuePeak,
                slowed.fast.p99(),
                slowed.fast.max(),
                slowed.fast.queuePeak,
                slowed.slow.p99(),
                slowed.slow.max(),
                slowed.slow.queuePeak,
                slowed.slowHandledAfter.toMillis());

        // the measurement can see a session being behind: the slow one waited for seconds, and filled its queue
        assertThat(slowed.slow.max())
                .as("the slow session's longest wait: it did fall behind")
                .isGreaterThan(Duration.ofSeconds(1).toMillis());
        assertThat(slowed.slow.queuePeak)
                .as("the slow session's queue: it filled")
                .isGreaterThan(50);
        // the other one is not
        assertThat(slowed.fast.handled).as("messages of the other session").isEqualTo(MESSAGES);
        assertThat(slowed.fast.max())
                .as("the other session's longest wait with a slow neighbour, ms")
                .isLessThanOrEqualTo(budget("slow.otherSession.waitMax.ms"));
        assertThat(slowed.fast.p99())
                .as("the other session's p99 wait with a slow neighbour, ms")
                .isLessThanOrEqualTo(budget("slow.otherSession.waitP99.ms"));
        assertThat(slowed.fast.queuePeak)
                .as("the other session's queue with a slow neighbour")
                .isLessThanOrEqualTo((int) budget("slow.otherSession.queuePeak"));
    }

    /** One session's numbers. */
    private static final class Observed {
        final AtomicInteger handledCount = new AtomicInteger();
        final long[] waits = new long[MESSAGES];
        volatile int handled;
        volatile int queuePeak;

        long p99() {
            return RestartStats.percentile(Arrays.copyOf(waits, handled), 99);
        }

        long max() {
            return RestartStats.percentile(Arrays.copyOf(waits, handled), 100);
        }
    }

    private record Outcome(Observed slow, Observed fast, Duration slowHandledAfter) {}

    /** Runs the two sessions over the same traffic, the live one's listener taking {@code slowBy} a message. */
    private static Outcome run(Duration slowBy) throws Exception {
        var slow = new Observed();
        var fast = new Observed();
        var slowDone = new CountDownLatch(MESSAGES);
        var fastDone = new CountDownLatch(MESSAGES);
        var slowListener = new Listener(slow, slowDone, slowBy);
        var fastListener = new Listener(fast, fastDone, Duration.ZERO);
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment(requireNonNull(broker).host(), api.apiHost(), broker.port())
                    .setMessagingSslContext(TestTls.clientContext())
                    .setAccessToken("slow-listener")
                    .build();
            var feed = new OddsFeed(new Quiet(), configuration);
            try {
                feed.getSessionBuilder()
                        .setListener(slowListener)
                        .setMessageInterest(MessageInterest.LIVE_ONLY)
                        .build();
                feed.getSessionBuilder()
                        .setListener(fastListener)
                        .setMessageInterest(MessageInterest.PREMATCH_ONLY)
                        .build();
                feed.open();
                awaitBound(requireNonNull(broker));

                var sampling = new AtomicReference<>(true);
                var sampler = Thread.ofPlatform().name("queue-sampler").start(() -> {
                    while (sampling.get()) {
                        var sessions = feed.getHealth().sessions();
                        slow.queuePeak =
                                Math.max(slow.queuePeak, sessions.get(0).queueDepth());
                        fast.queuePeak =
                                Math.max(fast.queuePeak, sessions.get(1).queueDepth());
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
                try {
                    var live = new String(Corpus.oddsChange(Corpus.SMALL), StandardCharsets.UTF_8);
                    var pre = FeedMessages.fromProducer(live, 2, 1);
                    long started = System.nanoTime();
                    for (int i = 0; i < MESSAGES; i++) {
                        assertThat(requireNonNull(broker)
                                        .publish(Fixtures.replace(
                                                live, "od:match:198314", "od:match:" + (7_000_000 + i))))
                                .as("a live message was routed")
                                .isTrue();
                        assertThat(broker.publish(
                                        Fixtures.replace(pre, "od:match:198314", "od:match:" + (8_000_000 + i))))
                                .as("a prematch message was routed")
                                .isTrue();
                    }
                    assertThat(fastDone.await(2, TimeUnit.MINUTES))
                            .as("the other session handled every message")
                            .isTrue();
                    assertThat(slowDone.await(2, TimeUnit.MINUTES))
                            .as("the slow session handled every message")
                            .isTrue();
                    return new Outcome(slow, fast, Duration.ofNanos(slowListener.lastDone.get() - started));
                } finally {
                    sampling.set(false);
                    sampler.interrupt();
                    sampler.join(10_000);
                }
            } finally {
                feed.close();
            }
        }
    }

    /** Probes each producer's scope until the broker routes it: a session's queue is bound. */
    private static void awaitBound(FakeFeed broker) throws InterruptedException {
        var probe = Fixtures.read("feed/fixture_change/fixture_change.xml");
        for (var message : new String[] {probe, FeedMessages.fromProducer(probe, 2, 1)}) {
            long until = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            while (!broker.publish(message)) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("a session's queue was not bound within a minute");
                }
                Thread.sleep(50);
            }
        }
    }

    private static long budget(String key) throws IOException {
        var budgets = new Properties();
        try (InputStream in =
                requireNonNull(SlowListenerTest.class.getResourceAsStream("/listener-budgets.properties"))) {
            budgets.load(in);
        }
        return Long.parseLong(requireNonNull(budgets.getProperty(key), key));
    }

    /** A session's listener: records how long each odds change waited in the SDK, then takes its time. */
    private static final class Listener implements OddsFeedListener {
        private final Observed observed;
        private final CountDownLatch done;
        private final Duration takes;
        final AtomicLong lastDone = new AtomicLong();

        Listener(Observed observed, CountDownLatch done, Duration takes) {
            this.observed = observed;
            this.done = done;
            this.takes = takes;
        }

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            var stamps = message.getTimestamp();
            int index = observed.handledCount.getAndIncrement();
            if (index < observed.waits.length) {
                observed.waits[index] = Math.max(0, stamps.getPublished() - stamps.getReceived());
                observed.handled = index + 1;
            }
            if (!takes.isZero()) {
                try {
                    Thread.sleep(takes);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastDone.set(System.nanoTime());
            done.countDown();
        }

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {}

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {}

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {}

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {}

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {}

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {}
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
