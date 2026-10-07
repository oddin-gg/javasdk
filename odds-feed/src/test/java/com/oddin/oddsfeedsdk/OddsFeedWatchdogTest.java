package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.feed.HealthMonitor;
import com.oddin.oddsfeedsdk.internal.feed.OpenFeeds;
import com.oddin.oddsfeedsdk.internal.feed.Watchdog;
import com.oddin.oddsfeedsdk.internal.recovery.Actors;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The feed's watchdog over a real broker: a wedged callback is found stalled, logged once and told,
 * and healthy again once it returns; the watchdog ends with the feed. With limits of seconds, not
 * those of the HTTP timeout.
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
    /** The timer threads alive before the test: those of no feed of this test. */
    private final Set<Thread> timersBefore = timerThreads();

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
            assertThat(timerThreadsOfThisTest())
                    .as("started with the feed, before it opens")
                    .hasSize(1);
            api.awaitQuiet();
            feed.close();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (!timerThreadsOfThisTest().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(timerThreadsOfThisTest()).as("ended with the close").isEmpty();
            // past the limit of a tick overdue: a watchdog stopped is not one wedged
            Thread.sleep(LIMITS.queue().plus(LIMITS.tick()).plusMillis(500).toMillis());
            assertThat(feed.getHealth().components()).containsEntry(HealthComponent.TIMERS, HealthState.HEALTHY);
            assertThat(logged).isEmpty();
        }
    }

    @Test
    void aRecoveryActorWedgedInOneTurnIsFoundStalledUntilItMovesAgain() throws Exception {
        try (var api = FakeRestServer.start()) {
            var heard = new Heard();
            var feed = feedAgainst(api, heard);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                feed.getSessionBuilder()
                        .setListener(new Wedged(new CountDownLatch(1), new CountDownLatch(0)))
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                var actor = requireNonNull(OpenFeeds.actor(requireNonNull(feed.running())));
                var once = new AtomicBoolean(true);
                long wedgedFrom = System.nanoTime();
                // the next fact it takes - a tick, at the latest, within a second - does not end
                Actors.beforeHandle(actor, () -> {
                    if (once.compareAndSet(true, false)) {
                        entered.countDown();
                        await(release);
                    }
                });
                assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                assertThat(part(feed, HealthComponent.RECOVERY).busySince() - wedgedFrom)
                        .as("the turn it is wedged in, begun since the hook was set, by System.nanoTime")
                        .isBetween(0L, System.nanoTime() - wedgedFrom);

                var stalled = heard.next(HealthComponent.RECOVERY);
                assertThat(stalled.state()).isEqualTo(HealthState.STALLED);
                assertThat(stalled.reason()).startsWith("the recovery actor has been in one turn for ");
                var health = feed.getHealth();
                assertThat(health.state()).isEqualTo(HealthState.STALLED);
                assertThat(health.components()).containsEntry(HealthComponent.RECOVERY, HealthState.STALLED);

                release.countDown();
                var healthy = heard.next(HealthComponent.RECOVERY);
                assertThat(healthy.state()).isEqualTo(HealthState.HEALTHY);
                assertThat(healthy.reason()).isEqualTo("the recovery actor moves again");
                awaitHealth(feed, now -> now.components().get(HealthComponent.RECOVERY) == HealthState.HEALTHY);
                api.awaitQuiet();
            } finally {
                release.countDown();
                feed.close();
            }
        }
    }

    @Test
    void theWatchdogsLimitsFollowTheConfiguredHttpTimeout() {
        for (var timeout : List.of(Duration.ofSeconds(30), Duration.ofMinutes(2))) {
            // never started: no host is reached
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("broker.example.invalid", "api.example.invalid")
                    .setAccessToken("token")
                    .setHttpClientTimeout(timeout)
                    .build();
            var feed = new OddsFeed(new Heard(), configuration);
            try {
                assertThat(feed.watchdog().limits())
                        .as("for an HTTP timeout of %s", timeout)
                        .isEqualTo(Watchdog.Limits.forHttpTimeout(timeout));
            } finally {
                feed.close();
            }
        }
        var defaults = new OddsFeed(
                new Heard(),
                OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment("broker.example.invalid", "api.example.invalid")
                        .setAccessToken("token")
                        .build());
        try {
            assertThat(defaults.watchdog().limits().callback())
                    .as("the default timeout of 30 s")
                    .isEqualTo(Duration.ofSeconds(65));
        } finally {
            defaults.close();
        }
    }

    @Test
    void theWatchdogReadsEachPartsBusySinceQueueAndCountFromWhatTheOpenBuilt() throws Exception {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api, new Heard());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                feed.getSessionBuilder()
                        .setListener(new Wedged(entered, release))
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                // what the watchdog reads of each part, beside what getHealth() counts of it
                var feedBroker = requireNonNull(broker);
                assertThat(feedBroker.publishFixture("feed/alive/alive.xml")).isTrue();
                var alive = awaitPart(feed, HealthComponent.ALIVES, part -> part.moved() > 0);
                var alives = feed.getHealth().alives();
                assertThat(alive.queued()).isZero().isEqualTo(alives.queued());
                assertThat(alive.moved()).isEqualTo(alives.handled());
                assertThat(alive.busySince()).isZero();
                // the session has the alive too
                var before = awaitPart(feed, HealthComponent.SESSION, part -> part.moved() == 1);
                assertThat(before.queued()).isZero();
                assertThat(before.busySince()).isZero();

                long published = System.nanoTime();
                for (int i = 0; i < 3; i++) {
                    assertThat(feedBroker.publishFixture(ODDS_CHANGE)).isTrue();
                }
                assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                var wedged = awaitPart(feed, HealthComponent.SESSION, part -> part.queued() == 2);
                var session = feed.getHealth().sessions().getFirst();
                assertThat(wedged.session()).isEqualTo(session.id());
                assertThat(wedged.busySince() - published)
                        .as("in the first odds change, since it was published, by System.nanoTime")
                        .isBetween(0L, System.nanoTime() - published);
                assertThat(wedged.queued()).isEqualTo(session.queueDepth());
                assertThat(wedged.moved()).as("the alive only").isOne().isEqualTo(session.handled());

                var consumer = awaitPart(feed, HealthComponent.CONSUMER, part -> part.busySince() == 0);
                assertThat(consumer.moved()).as("hand-offs run").isPositive();
                assertThat(consumer.queued()).as("none waiting for a thread").isZero();

                release.countDown();
                var handled = awaitPart(feed, HealthComponent.SESSION, part -> part.moved() == 4);
                assertThat(handled.queued()).isZero();
                assertThat(handled.busySince()).isZero();
                assertThat(feed.getHealth().sessions().getFirst().handled()).isEqualTo(4);

                var events = feed.events();
                // a sample taken in a callback has moved == delivered too: the running one counts once done
                var delivered = awaitPart(
                        feed,
                        HealthComponent.EVENTS,
                        part -> part.queued() == 0 && part.moved() == events.delivered() && part.busySince() == 0);
                assertThat(delivered.moved())
                        .as("the connection's change at least")
                        .isPositive();
                assertThat(delivered.busySince()).isZero();
                api.awaitQuiet();
            } finally {
                release.countDown();
                feed.close();
            }
        }
    }

    @Test
    void theWatchdogsTickReadsTheWholeHealthThoughNobodyReadsIt() throws Exception {
        try (var api = FakeRestServer.start()) {
            // for a limit of nothing every catalog is stale at once: a staleness only a read of the
            // whole health finds, and nobody but the watchdog's tick reads it here
            var feed = feedAgainst(api, new Heard(), Duration.ZERO);
            try {
                feed.getProducerManager();
                assertThat(logged(HealthComponent.CATALOGS, 1)).singleElement().satisfies(degraded -> {
                    assertThat(degraded.state()).isEqualTo(HealthState.DEGRADED);
                    assertThat(degraded.reason()).startsWith("the ").contains(" have served a value stale for ");
                });
                api.awaitQuiet();
            } finally {
                feed.close();
            }
        }
    }

    // ------------------------------------------------------------------ support

    private OddsFeed feedAgainst(FakeRestServer api, GlobalEventsListener listener) {
        return feedAgainst(api, listener, HealthMonitor.CATALOG_STALE_LIMIT);
    }

    private OddsFeed feedAgainst(FakeRestServer api, GlobalEventsListener listener, Duration catalogStaleLimit) {
        var feed = requireNonNull(broker);
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment(feed.host(), api.apiHost(), feed.port())
                .setMessagingSslContext(TestTls.clientContext())
                .setAccessToken("token")
                .build();
        return new OddsFeed(listener, configuration, null, LIMITS, logged::add, catalogStaleLimit);
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

    /** The watchdog's reading of the part, once it satisfies {@code ready} or {@link #WAIT} has passed. */
    private static Watchdog.Sample awaitPart(OddsFeed feed, HealthComponent component, Predicate<Watchdog.Sample> ready)
            throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        var part = part(feed, component);
        while (!ready.test(part) && System.nanoTime() < until) {
            Thread.sleep(20);
            part = part(feed, component);
        }
        assertThat(ready.test(part))
                .as("the %s within %s: %s", component, WAIT, part)
                .isTrue();
        return part;
    }

    private static Watchdog.Sample part(OddsFeed feed, HealthComponent component) {
        return Watchdog.parts(feed.events(), feed.running()).stream()
                .filter(part -> part.component() == component)
                .findFirst()
                .orElseThrow();
    }

    /** The timer threads alive now but not before the test: one alive before it is no feed's of this test. */
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
