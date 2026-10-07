package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static com.oddin.oddsfeed.fakes.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A producer going down and coming back, and what the SDK does with producers it does not know.
 * {@link ProducerStatusScenarioIT} has the first recovery and the first down; this picks up from
 * there.
 */
class ProducerRecoveryScenarioIT {

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

    /** A live odds change from producer 1, carrying no request id, so not a recovery's. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            FeedMessages.fromProducer(Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), 2, 1),
            " request_id=\"2049987833\"",
            "");

    /**
     * After a down, the SDK asks for a recovery from where it left off, not for everything: from
     * the last alive it saw while the producer was up, which says everything before it has been
     * sent. The snapshot complete of that recovery brings the producer back.
     */
    @Test
    void aProducerThatWentDownRecoversFromTheLastAliveItSawWhileUp() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            // every alive stamped in the order a producer sends them, the first before the others
            long lastAliveWhileUp = System.currentTimeMillis() - 2_000;
            feed.publishAsIs(stampedAt(alive(1, true), lastAliveWhileUp - 1_000));
            feed.publish(snapshotComplete(
                    1,
                    requestId(rest.awaitRequests("POST", PREMATCH_RECOVERY, 1).getFirst())));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the first recovery")
                    .isFalse();

            // stamped a second apart, so the recovery point says which of the two alives it came from
            long unsubscribedAt = lastAliveWhileUp + 1_000;
            feed.publishAsIs(stampedAt(alive(1, true), lastAliveWhileUp));
            feed.publishAsIs(stampedAt(alive(1, false), unsubscribedAt));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the unsubscribed alive")
                    .isTrue();

            RecordedRequest recovery =
                    rest.awaitRequests("POST", PREMATCH_RECOVERY, 2).get(1);
            assertThat(recovery.parameter("after"))
                    .as("where the recovery after the down starts; none would mean a full snapshot")
                    .isNotNull();
            assertThat(Long.parseLong(recovery.parameter("after")))
                    .as("where the recovery after the down starts: no earlier than the last alive while up")
                    .isBetween(lastAliveWhileUp, unsubscribedAt);

            feed.publish(snapshotComplete(1, requestId(recovery)));
            var back = sdk.events().nextProducerStatus(1);
            assertThat(back.isDown()).as("down after the second recovery").isFalse();
            assertThat(back.getProducerStatusReason())
                    .as("reason")
                    .isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
            assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                    .as("producer 1 down")
                    .isFalse();
        }
    }

    /**
     * A recovery-from timestamp the client sets before the feed opens is where the first recovery
     * of that producer starts on 1.0, as a client resuming after a restart wants it to. 0.0.x
     * forgets it: open() fetches the producer list again and replaces what the setter wrote, so
     * the first recovery asks for a full snapshot (KD-32).
     */
    @Test
    void theRecoveryTimestampTheClientSetsIsWhereTheFirstRecoveryStarts() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            long from = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
            sdk.oddsFeed().getProducerManager().setProducerRecoveryFromTimestamp(1, from);
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, true));

            String after = rest.awaitRequest("POST", PREMATCH_RECOVERY).parameter("after");
            KnownDifference.RECOVERY_FROM_SET_BEFORE_OPEN_IS_FORGOTTEN.expect(
                    () -> assertThat(after)
                            .as("where the first recovery of producer 1 starts; none is a full snapshot")
                            .isNull(),
                    () -> assertThat(after)
                            .as("where the first recovery of producer 1 starts")
                            .isEqualTo(Long.toString(from)));
        }
    }

    /**
     * The recovery timestamp a client reads to resume from: 0.0.x reports the last alive on the
     * SDK's alive channel, which is ahead of a message the session is still in the callback of; 1.0
     * reports what the session has processed, so a client that resumes from it misses nothing
     * (KD-28).
     */
    @Test
    void theRecoveryTimestampIsWhatTheSessionProcessedNotALaterAlive() throws InterruptedException {
        var listener = new HoldingTheSecond();
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.oddsFeed()
                    .getSessionBuilder()
                    .setListener(listener)
                    .setMessageInterest(MessageInterest.ALL)
                    .build();
            sdk.oddsFeed().open();
            feed.publish(alive(1, true));
            feed.publish(snapshotComplete(
                    1,
                    requestId(rest.awaitRequests("POST", PREMATCH_RECOVERY, 1).getFirst())));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the first recovery")
                    .isFalse();

            try {
                long processedAt = System.currentTimeMillis();
                feed.publishAsIs(stampedAt(LIVE_ODDS_CHANGE, processedAt));
                assertThat(listener.first.await(Received.DELIVERY.toSeconds(), TimeUnit.SECONDS))
                        .as("the first odds change processed")
                        .isTrue();
                ProducerManager producers = sdk.oddsFeed().getProducerManager();
                feed.publishAsIs(stampedAt(LIVE_ODDS_CHANGE, processedAt + 1));
                assertThat(listener.held.await(Received.DELIVERY.toSeconds(), TimeUnit.SECONDS))
                        .as("the second odds change in its callback")
                        .isTrue();
                Thread.sleep(20);
                long aliveAt = System.currentTimeMillis();
                feed.publishAsIs(stampedAt(alive(1, true), aliveAt));

                KnownDifference.RECOVERY_TIMESTAMP_RUNS_AHEAD.expect(
                        () -> assertThat(awaitTimestampForRecovery(producers, aliveAt))
                                .as("recovery timestamp of producer 1, with the second odds change in its callback")
                                .isEqualTo(Instant.ofEpochMilli(aliveAt)),
                        () -> {
                            assertThat(awaitTimestampForRecovery(producers, processedAt))
                                    .as("recovery timestamp of producer 1 once the first odds change is processed")
                                    .isEqualTo(Instant.ofEpochMilli(processedAt));
                            // the alive has nothing to move: it stays as it was
                            Thread.sleep(1_000);
                            assertThat(producers.getProducer(1).getTimestampForRecovery())
                                    .as("recovery timestamp of producer 1, with the second odds change in its callback")
                                    .isEqualTo(Instant.ofEpochMilli(processedAt));
                        });
            } finally {
                listener.release.countDown();
            }
        }
    }

    /**
     * Every producer starts down, and 0.0.x reports a status change only when the down flag or its
     * reason changes: an alive saying a producer that is still down is unsubscribed changes neither,
     * so the client hears nothing, while the SDK does ask for a recovery. 1.0 does the same; the
     * changed cause reaches only onProducerCauseChange, which 1.0 adds.
     */
    @Test
    void anUnsubscribedAliveForAProducerStillDownIsNotReported() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, false));

            KnownDifference.Check nothingReported = () -> {
                assertThat(rest.awaitRequest("POST", PREMATCH_RECOVERY))
                        .as("a recovery of producer 1")
                        .isNotNull();
                assertThat(sdk.events().pollProducerStatus(1, Duration.ofSeconds(1)))
                        .as("a status change of producer 1")
                        .isEmpty();
                assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                        .as("producer 1 down")
                        .isTrue();
            };
            KnownDifference.ALREADY_DOWN_PRODUCER_REPORTS_NOTHING.expect(nothingReported, nothingReported);
        }
    }

    /**
     * A message from a producer the producer list does not have never reaches the listener; the
     * next one, from a known producer, does. 0.0.x logs a warning and makes the unknown producer up
     * on request, with made-up details.
     */
    @Test
    void aMessageFromAnUnknownProducerIsNotDelivered() throws InterruptedException {
        try (LogCapture logs = LogCapture.start();
                FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            var fromProducer7 = FeedMessages.fromProducer(Fixtures.read("feed/bet_stop/bet_stop_all_groups.xml"), 2, 7);
            assertThat(feed.publish(fromProducer7))
                    .as("routed to the SDK's queue")
                    .isTrue();
            feed.publishFixture("feed/odds_change/odds_change_markets_only.xml");

            assertThat(received.next(Message.class))
                    .as("the first message the listener gets")
                    .isInstanceOf(OddsChange.class);
            ProducerManager producers = sdk.oddsFeed().getProducerManager();
            KnownDifference.UNKNOWN_PRODUCER_IS_MADE_UP.expect(
                    () -> {
                        assertThat(logs.warningsFrom("com.oddin"))
                                .as("warnings")
                                .anySatisfy(warning -> assertThat(warning).endsWith("Creating unknown producer: 7"));
                        Producer unknown = producers.getProducer(7);
                        assertThat(unknown.getName()).as("name of producer 7").isEqualTo("unknown");
                        assertThat(unknown.getDescription())
                                .as("description of producer 7")
                                .isEqualTo("unknown producer");
                    },
                    () -> assertThat(producers.getProducer(7))
                            .as("producer 7, which the producer list does not have")
                            .isNull());
        }
    }

    /**
     * The producer list gives a producer's scopes as one attribute; one that serves both reads
     * {@code live|prematch}. 0.0.x splits it on the two characters {@code \|} rather than on the
     * pipe, so such a producer ends up with no scope at all, and a live-only or prematch-only
     * session never enables it.
     */
    @Test
    void aProducerListedWithBothScopesHasBoth() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            rest.respond(
                    "/v1/descriptions/producers",
                    200,
                    Fixtures.replace(
                            Fixtures.read("rest/producers/producers.xml"),
                            "scope=\"live\"",
                            "scope=\"live|prematch\""));

            Producer live = sdk.oddsFeed().getProducerManager().getProducer(2);
            assertThat(live.getName()).as("producer 2").isEqualTo("live");
            KnownDifference.PRODUCER_IN_BOTH_SCOPES_HAS_NONE.expect(
                    () -> assertThat(live.getProducerScopes())
                            .as("scopes of producer 2, listed as live|prematch")
                            .isEmpty(),
                    () -> assertThat(live.getProducerScopes())
                            .as("scopes of producer 2, listed as live|prematch")
                            .containsExactlyInAnyOrder(ProducerScope.LIVE, ProducerScope.PREMATCH));
        }
    }

    /**
     * Producer 1's recovery timestamp once it reads {@code expected}, or as it reads when {@link
     * Received#DELIVERY} is over.
     */
    private static Instant awaitTimestampForRecovery(ProducerManager producers, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Received.DELIVERY.toNanos();
        while (!Instant.ofEpochMilli(expected).equals(producers.getProducer(1).getTimestampForRecovery())
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return producers.getProducer(1).getTimestampForRecovery();
    }

    /** A session listener that holds its second odds change in the callback until released. */
    private static final class HoldingTheSecond implements OddsFeedListener {
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger oddsChanges = new AtomicInteger();

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            if (oddsChanges.incrementAndGet() == 1) {
                first.countDown();
                return;
            }
            held.countDown();
            try {
                release.await(Received.DELIVERY.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

        // abstract on 0.0.x, a default on 1.0
        @Override
        public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {}
    }

    private static long requestId(RecordedRequest recovery) {
        var requestId = recovery.parameter("request_id");
        assertThat(requestId).as("request id of " + recovery.path()).isNotBlank();
        return Long.parseLong(requestId);
    }
}
