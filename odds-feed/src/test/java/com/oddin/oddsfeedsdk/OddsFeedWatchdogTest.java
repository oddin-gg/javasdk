package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.feed.Watchdog;
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
import com.oddin.oddsfeedsdk.subscribe.ConnectionState;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The feed's watchdog over a real broker: a wedged callback is found stalled, logged once and told,
 * and healthy again once it returns; the watchdog ends with the feed. With limits of seconds, not
 * the default 30 s.
 */
class OddsFeedWatchdogTest {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final Duration WAIT = Duration.ofSeconds(20);
    /**
     * Past the recovery actor's turn of a second, which it begins however idle: the limits a test
     * sets stay above it. A machine under load can still stretch an idle turn past them, so a test
     * reads what was logged of the part it wedges only.
     */
    private static final Watchdog.Limits LIMITS =
            new Watchdog.Limits(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(100));

    private static @Nullable FakeFeed broker;

    /** The changes the feed's health logged, in order. */
    private final List<HealthEvent> logged = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startTheBroker() {
        broker = FakeFeed.start();
    }

    @AfterAll
    static void stopTheBroker() {
        requireNonNull(broker).close();
    }

    @Test
    void aSessionsCallbackThatDoesNotReturnStallsItsSessionUntilItReturns() throws Exception {
        try (var api = FakeRestServer.start()) {
            var heard = new Heard();
            var feed = feedAgainst(api, heard);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                var session = feed.getSessionBuilder()
                        .setListener(new Wedged(entered, release))
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                assertThat(requireNonNull(broker).publishFixture(ODDS_CHANGE)).isTrue();
                assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

                var stalled = heard.next(HealthComponent.SESSION);
                assertThat(stalled.session()).isSameAs(session);
                assertThat(stalled.previous()).isEqualTo(HealthState.HEALTHY);
                assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
                assertThat(stalled.reason()).startsWith("session 1 has been in one message for ");
                var health = feed.getHealth();
                assertThat(health.state()).isEqualTo(HealthState.STALLED);
                assertThat(health.components()).containsEntry(HealthComponent.SESSION, HealthState.STALLED);
                assertThat(health.sessions().getFirst().state()).isEqualTo(HealthState.STALLED);
                // a few more looks, which find it still stalled
                Thread.sleep(500);
                assertThat(logged(HealthComponent.SESSION, 1)).as("logged once").containsExactly(stalled);

                release.countDown();
                var healthy = heard.next(HealthComponent.SESSION);
                assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
                assertThat(healthy.reason()).isEqualTo("session 1 moves again");
                assertThat(feed.getHealth().sessions().getFirst().state()).isEqualTo(HealthState.HEALTHY);
                assertThat(logged(HealthComponent.SESSION, 2)).containsExactly(stalled, healthy);
                api.awaitQuiet();
            } finally {
                release.countDown();
                feed.close();
            }
        }
    }

    @Test
    void aWedgedEventsListenerIsFoundStalledThoughItCannotHearIt() throws Exception {
        try (var api = FakeRestServer.start()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var heard = new Heard() {
                @Override
                public void onConnectionStateChange(ConnectionStateChange change) {
                    if (change.state() == ConnectionState.UP) {
                        entered.countDown();
                        await(release);
                    }
                }
            };
            var feed = feedAgainst(api, heard);
            try {
                feed.getSessionBuilder()
                        .setListener(new Wedged(new CountDownLatch(1), new CountDownLatch(0)))
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

                var health =
                        awaitHealth(feed, read -> read.components().get(HealthComponent.EVENTS) == HealthState.STALLED);
                assertThat(health.state()).isEqualTo(HealthState.STALLED);
                assertThat(logged(HealthComponent.EVENTS, 1)).singleElement().satisfies(stalled -> {
                    assertThat(stalled.component()).isEqualTo(HealthComponent.EVENTS);
                    assertThat(stalled.reason()).startsWith("the events thread has been in one callback for ");
                });

                release.countDown();
                awaitHealth(feed, read -> read.components().get(HealthComponent.EVENTS) == HealthState.HEALTHY);
                assertThat(logged(HealthComponent.EVENTS, 2))
                        .extracting(HealthEvent::state)
                        .containsExactly(HealthState.STALLED, HealthState.HEALTHY);
                api.awaitQuiet();
            } finally {
                release.countDown();
                feed.close();
            }
        }
    }

    @Test
    void withTheWatchdogsOwnThreadWedgedTheHealthFindsTheStallAndTheTimersItself() throws Exception {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api, new Heard());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var wedgedTimer = new CountDownLatch(1);
            var releaseTimer = new CountDownLatch(1);
            try {
                feed.getSessionBuilder()
                        .setListener(new Wedged(entered, release))
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.watchdog().beforeTick(() -> {
                    wedgedTimer.countDown();
                    await(releaseTimer);
                });
                feed.open();
                assertThat(wedgedTimer.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
                assertThat(requireNonNull(broker).publishFixture(ODDS_CHANGE)).isTrue();
                assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

                var health = awaitHealth(
                        feed,
                        read -> read.components().get(HealthComponent.SESSION) == HealthState.STALLED
                                && read.components().get(HealthComponent.TIMERS) == HealthState.STALLED);
                assertThat(health.sessions().getFirst().state()).isEqualTo(HealthState.STALLED);

                release.countDown();
                awaitHealth(feed, read -> read.components().get(HealthComponent.SESSION) == HealthState.HEALTHY);
                feed.watchdog().beforeTick(() -> {});
                releaseTimer.countDown();
                awaitHealth(feed, read -> read.state() == HealthState.HEALTHY);
                api.awaitQuiet();
            } finally {
                release.countDown();
                releaseTimer.countDown();
                feed.close();
            }
        }
    }

    @Test
    void theWatchdogStartsWithTheFeedAndEndsWithItsClose() throws Exception {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api, new Heard());
            feed.getProducerManager();
            assertThat(timerThreads())
                    .as("started with the feed, before it opens")
                    .isOne();
            api.awaitQuiet();
            feed.close();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (timerThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(timerThreads()).as("ended with the close").isZero();
            // past the limit of a tick overdue: a watchdog stopped is not one wedged
            Thread.sleep(LIMITS.queue().plus(LIMITS.tick()).plusMillis(500).toMillis());
            assertThat(feed.getHealth().components()).containsEntry(HealthComponent.TIMERS, HealthState.HEALTHY);
            assertThat(logged).isEmpty();
        }
    }

    // ------------------------------------------------------------------ support

    private OddsFeed feedAgainst(FakeRestServer api, GlobalEventsListener listener) {
        var feed = requireNonNull(broker);
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment(feed.host(), api.apiHost(), feed.port())
                .setMessagingSslContext(TestTls.clientContext())
                .setAccessToken("token")
                .build();
        return new OddsFeed(listener, configuration, null, LIMITS, logged::add);
    }

    /**
     * What was logged of the component, once it has {@code count} lines or {@link #WAIT} has passed:
     * the health shows a change before the thread that tells it has logged it.
     */
    private List<HealthEvent> logged(HealthComponent component, int count) throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        var of = loggedOf(component);
        while (of.size() < count && System.nanoTime() < until) {
            Thread.sleep(20);
            of = loggedOf(component);
        }
        return of;
    }

    private List<HealthEvent> loggedOf(HealthComponent component) {
        return logged.stream().filter(event -> event.component() == component).toList();
    }

    private static FeedHealth awaitHealth(OddsFeed feed, Predicate<FeedHealth> ready) throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        var health = feed.getHealth();
        while (!ready.test(health) && System.nanoTime() < until) {
            Thread.sleep(20);
            health = feed.getHealth();
        }
        assertThat(ready.test(health))
                .as("the health within %s: %s", WAIT, health)
                .isTrue();
        return health;
    }

    private static long timerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("oddsfeed-timer") && thread.isAlive())
                .count();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Hears the health events. */
    private static class Heard implements GlobalEventsListener {
        private final BlockingQueue<HealthEvent> health = new LinkedBlockingQueue<>();

        HealthEvent next(HealthComponent component) throws InterruptedException {
            long until = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < until) {
                var next = health.poll(100, TimeUnit.MILLISECONDS);
                if (next != null && next.component() == component) {
                    return next;
                }
            }
            throw new AssertionError("no health event of the " + component + " within " + WAIT);
        }

        @Override
        public void onHealthEvent(HealthEvent event) {
            health.add(event);
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }

    /** A session's listener whose first odds change does not return until released. */
    private static final class Wedged implements OddsFeedListener {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        Wedged(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            entered.countDown();
            await(release);
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
}
