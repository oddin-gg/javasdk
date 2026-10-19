package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.cache.LiveState;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.feed.HealthMonitor;
import com.oddin.oddsfeedsdk.internal.feed.OpenFeeds;
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
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import com.oddin.oddsfeedsdk.subscribe.ProducerCauseChange;
import com.oddin.oddsfeedsdk.subscribe.ProducerStatusCause;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A step of the wall clock under a running feed: the producers stay up, nothing is asked for again
 * and the health is as it was, since what the recovery machine measures runs on the monotonic
 * clock; the machine still measures, as a producer that goes silent goes down after the
 * inactivity limit by that clock. The feed here is open against the fake broker and API, with a
 * wall clock the test shifts and the monotonic one of the system.
 */
class OddsFeedClockStepTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    /**
     * The maximum inactivity, as configured: more than the default, for the real seconds the steps take
     * to run while a producer is still quiet to stay well inside it, and a step of 45 seconds is more
     * than it.
     */
    private static final int INACTIVITY_SECONDS = 30;

    private static final long PRODUCER = 1;
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String SUMMARY = "/v1/sports/en/sport_events/od:match:198314/summary";
    private static final String MATCH_SUMMARY = "rest/match_summary/match_summary.xml";
    private static final String ODDS_CHANGE_CLOSED = "feed/odds_change/odds_change_closed_with_winner.xml";
    private static final String INITIATE = "/v1/pre/recovery/initiate_request";

    private static @Nullable FakeFeed broker;

    @BeforeAll
    static void startTheBroker() {
        broker = FakeFeed.start();
    }

    @AfterAll
    static void stopTheBroker() {
        requireNonNull(broker).close();
    }

    @ParameterizedTest
    @ValueSource(longs = {45, -45})
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void
            aStepOfTheWallClockMovesNoProducerDownAsksForNoRecoveryAndLeavesTheHealthAndAProducerThatGoesSilentStillGoesDown(
                    long seconds) throws Exception {
        var broker = requireNonNull(OddsFeedClockStepTest.broker);
        var shift = new AtomicLong();
        InstantSource wall = () -> Instant.now().plusMillis(shift.get());
        var heard = new Heard();
        var ticks = new Semaphore(0);
        try (var api = FakeRestServer.start()) {
            var feed = newFeed(broker, api, wall, heard);
            try {
                feed.getSessionBuilder()
                        .setListener(new Silent())
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                var actor = requireNonNull(OpenFeeds.actor(requireNonNull(feed.running())));
                Actors.beforeTick(actor, ticks::release);

                // the producer up: an alive, the recovery it asks for, and that one's snapshot complete
                long lastAlive = System.nanoTime();
                assertThat(broker.publish(FeedMessages.alive(1, true))).isTrue();
                var recovery = api.awaitRequest("POST", INITIATE);
                assertThat(broker.publish(
                                FeedMessages.snapshotComplete(1, Long.parseLong(recovery.parameter("request_id")))))
                        .isTrue();
                assertThat(heard.awaitStatus(false)).as("producer 1 up").isTrue();
                // the cause is told after the status: both, before they are counted
                heard.awaitCause(false, WAIT);
                var producer = requireNonNull(feed.getProducerManager().getProducer(PRODUCER));
                assertThat(producer.isFlaggedDown()).isFalse();
                int statusChanges = heard.statuses.size();
                int causeChanges = heard.causes.size();
                int requests = recoveryRequests(api);
                var health = steady(feed.getHealth());
                assertThat(requests)
                        .as("the one recovery the producer's first alive asked for")
                        .isEqualTo(1);

                // a step, another to the other side of where the clock was, and one back to where it was:
                // the ticks after each would have taken the producer down by the wall clock. No alive
                // comes meanwhile; the next test sends one after each step
                long lastMessage = producer.getLastMessageTimestamp();
                heard.forget();
                for (long step : new long[] {seconds, -2 * seconds, seconds}) {
                    shift.addAndGet(Duration.ofSeconds(step).toMillis());
                    // the first tick begun after the step ends before the second begins
                    awaitTicks(ticks, 2);

                    assertThat(heard.statuses)
                            .as("status changes after a step of " + step + " s")
                            .hasSize(statusChanges);
                    assertThat(heard.causes).hasSize(causeChanges);
                    assertThat(producer.isFlaggedDown()).isFalse();
                    assertThat(recoveryRequests(api)).as("recoveries asked for").isEqualTo(requests);
                    assertThat(steady(feed.getHealth())).as("health").isEqualTo(health);
                    assertThat(producer.getLastMessageTimestamp())
                            .as("a step moves no time the SDK published")
                            .isEqualTo(lastMessage);
                }
                assertThat(shift.get()).as("the clock is where it was").isZero();

                // genuinely silent: it goes down after the inactivity limit, which the machine still measures
                var down = heard.awaitCause(true, WAIT.plusSeconds(INACTIVITY_SECONDS));
                assertThat(down.cause()).isEqualTo(ProducerStatusCause.ALIVE_INTERVAL_VIOLATION);
                assertThat(Duration.ofNanos(System.nanoTime() - lastAlive))
                        .as("the time it was quiet")
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(INACTIVITY_SECONDS - 1));
                assertThat(producer.isFlaggedDown()).isTrue();
                assertThat(recoveryRequests(api))
                        .as("nothing asked for while it was down")
                        .isEqualTo(requests);

                // and an alive brings it back through a recovery of its own
                assertThat(broker.publish(FeedMessages.alive(1, true))).isTrue();
                assertThat(api.awaitRequests("POST", INITIATE, 2)).hasSize(2);
            } finally {
                feed.close();
            }
        }
    }

    /**
     * The same steps, with an alive after each: the session's copy of it can be handled before the
     * alive channel's copy measures the clock offset anew, and read against the offset measured before
     * the step it would be as old as the step. It must not be.
     */
    @ParameterizedTest
    @ValueSource(longs = {45, -45})
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anAliveAfterAStepOfTheWallClockIsNotTakenForAProcessingDelay(long seconds) throws Exception {
        var broker = requireNonNull(OddsFeedClockStepTest.broker);
        var shift = new AtomicLong();
        InstantSource wall = () -> Instant.now().plusMillis(shift.get());
        var heard = new Heard();
        var ticks = new Semaphore(0);
        try (var api = FakeRestServer.start()) {
            var feed = newFeed(broker, api, wall, heard);
            try {
                feed.getSessionBuilder()
                        .setListener(new Silent())
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                var actor = requireNonNull(OpenFeeds.actor(requireNonNull(feed.running())));
                Actors.beforeTick(actor, ticks::release);

                assertThat(broker.publish(FeedMessages.alive(1, true))).isTrue();
                var recovery = api.awaitRequest("POST", INITIATE);
                assertThat(broker.publish(
                                FeedMessages.snapshotComplete(1, Long.parseLong(recovery.parameter("request_id")))))
                        .isTrue();
                assertThat(heard.awaitStatus(false)).as("producer 1 up").isTrue();
                heard.awaitCause(false, WAIT);
                var producer = requireNonNull(feed.getProducerManager().getProducer(PRODUCER));
                int statusChanges = heard.statuses.size();
                int causeChanges = heard.causes.size();
                int requests = recoveryRequests(api);
                heard.forget();

                for (long step : new long[] {seconds, -2 * seconds, seconds}) {
                    shift.addAndGet(Duration.ofSeconds(step).toMillis());
                    long processed = producer.getLastProcessedMessageGenTimestamp();
                    assertThat(broker.publish(FeedMessages.alive(1, true))).isTrue();
                    awaitTicks(ticks, 3);

                    assertThat(producer.getLastProcessedMessageGenTimestamp())
                            .as("the session took the alive after a step of " + step + " s")
                            .isGreaterThan(processed);
                    assertThat(heard.statuses)
                            .as("status changes after an alive after a step of " + step + " s")
                            .hasSize(statusChanges);
                    assertThat(heard.causes).hasSize(causeChanges);
                    assertThat(producer.isFlaggedDown()).isFalse();
                    assertThat(recoveryRequests(api)).as("recoveries asked for").isEqualTo(requests);
                }
                assertThat(shift.get()).as("the clock is where it was").isZero();
            } finally {
                feed.close();
            }
        }
    }

    /**
     * The feed hands its own clock to the caches it starts: the status a live message wrote is the
     * feed's until the feed has been quiet for the status age by the monotonic clock, and a step of the
     * wall clock of more than that age moves nothing. Read the other way round, a feed that handed
     * them the system's clock would not go quiet by the clock the test shifts.
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theLiveStatusOfAMatchIsTheFeedsUntilTheFeedClocksMonotonicSideSaysItIsQuiet() throws Exception {
        var broker = requireNonNull(OddsFeedClockStepTest.broker);
        var shift = new AtomicLong();
        var elapsed = new AtomicLong();
        InstantSource wall = () -> Instant.now().plusMillis(shift.get());
        var clock = FeedClock.of(wall, () -> System.nanoTime() + elapsed.get());
        var heard = new Heard();
        var told = new CompletableFuture<Void>();
        var summary = Fixtures.read(MATCH_SUMMARY)
                .replace("home_score=\"3\" away_score=\"2\"", "home_score=\"7\" away_score=\"6\"");
        try (var api = FakeRestServer.start()) {
            api.respond(SUMMARY, 200, summary);
            var feed = newFeed(broker, api, heard, clock);
            try {
                feed.getSessionBuilder()
                        .setListener(new Silent() {
                            @Override
                            public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
                                told.complete(null);
                            }
                        })
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                assertThat(broker.publishFixture(ODDS_CHANGE_CLOSED)).isTrue();
                told.get(WAIT.toSeconds(), TimeUnit.SECONDS);

                var match = requireNonNull(feed.getSportsInfoManager().getMatch(MATCH));
                var status = requireNonNull(match.getStatus());
                assertThat(status.getHomeScore())
                        .as("the feed's, as the message sent it")
                        .isEqualTo(3.0);
                int summaries = api.requests("GET", SUMMARY).size();

                // a step of the wall clock of a day each way: the feed is no quieter for it
                for (long step : new long[] {24 * 3600, -48 * 3600}) {
                    shift.addAndGet(Duration.ofSeconds(step).toMillis());
                    assertThat(status.getHomeScore())
                            .as("after a step of " + step + " s")
                            .isEqualTo(3.0);
                    assertThat(api.requests("GET", SUMMARY))
                            .as("no summary fetched for it")
                            .hasSize(summaries);
                }

                // the monotonic clock past the status age: the feed is quiet, and the summary's status is read
                elapsed.addAndGet(LiveState.STATUS_AGE.plusMinutes(1).toNanos());
                assertThat(status.getHomeScore()).as("the summary's").isEqualTo(7.0);
                assertThat(api.requests("GET", SUMMARY)).hasSize(summaries + 1);
            } finally {
                feed.close();
            }
        }
    }

    /** A feed over the fake broker and API, its clock's wall side the given source; not yet opened. */
    private static OddsFeed newFeed(FakeFeed broker, FakeRestServer api, InstantSource wall, Heard heard) {
        return newFeed(broker, api, heard, FeedClock.of(wall, System::nanoTime));
    }

    /** A feed over the fake broker and API on the given clock; not yet opened. */
    private static OddsFeed newFeed(FakeFeed broker, FakeRestServer api, Heard heard, FeedClock clock) {
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                .setMessagingSslContext(TestTls.clientContext())
                .setAccessToken("token")
                .setMaxInactivitySeconds(INACTIVITY_SECONDS)
                .build();
        return new OddsFeed(
                heard,
                configuration,
                null,
                null,
                HealthMonitor.Log.SLF4J,
                configuration.getCatalogStaleLimit(),
                System::nanoTime,
                clock);
    }

    /** The health without what a step could legitimately move: when it was read, and the queues' and the alives' counts. */
    private static List<Object> steady(FeedHealth health) {
        return List.of(health.state(), health.components(), health.recovery());
    }

    private static int recoveryRequests(FakeRestServer api) {
        return (int) api.requests().stream()
                .filter(request -> request.path().contains("/recovery/"))
                .count();
    }

    /** Waits until the recovery actor has taken {@code n} ticks of its own after the call. */
    private static void awaitTicks(Semaphore ticks, int n) throws InterruptedException {
        ticks.drainPermits();
        assertThat(ticks.tryAcquire(n, WAIT.toSeconds(), TimeUnit.SECONDS))
                .as(n + " ticks of the recovery actor")
                .isTrue();
    }

    /** What the feed told about producer 1. */
    private static final class Heard implements GlobalEventsListener {
        private final List<ProducerStatus> statuses = new CopyOnWriteArrayList<>();
        private final List<ProducerCauseChange> causes = new CopyOnWriteArrayList<>();
        private final BlockingQueue<ProducerStatus> statusQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<ProducerCauseChange> causeQueue = new LinkedBlockingQueue<>();

        @Override
        public void onProducerStatusChange(ProducerStatus status) {
            var producer = status.getProducer();
            if (producer != null && producer.getId() == PRODUCER) {
                statuses.add(status);
                statusQueue.add(status);
            }
        }

        @Override
        public void onProducerCauseChange(ProducerCauseChange change) {
            if (change.producerId() == PRODUCER) {
                causes.add(change);
                causeQueue.add(change);
            }
        }

        /** Drops what has been heard and not read. */
        void forget() {
            statusQueue.clear();
            causeQueue.clear();
        }

        /** Whether a change to this state came within the wait. */
        boolean awaitStatus(boolean down) throws InterruptedException {
            long until = System.nanoTime() + WAIT.toNanos();
            for (var status = statusQueue.poll(WAIT.toNanos(), TimeUnit.NANOSECONDS);
                    status != null;
                    status = statusQueue.poll(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                if (status.isDown() == down) {
                    return true;
                }
            }
            return false;
        }

        ProducerCauseChange awaitCause(boolean down, Duration wait) throws InterruptedException {
            long until = System.nanoTime() + wait.toNanos();
            for (var change = causeQueue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
                    change != null;
                    change = causeQueue.poll(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                if (change.down() == down) {
                    return change;
                }
            }
            throw new AssertionError("no change of producer " + PRODUCER + " to down=" + down + " within " + wait);
        }

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(com.oddin.oddsfeedsdk.schema.utils.URN eventId, long requestId) {}
    }

    /** A session's listener for a feed that is sent no data. */
    private static class Silent implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {}

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
