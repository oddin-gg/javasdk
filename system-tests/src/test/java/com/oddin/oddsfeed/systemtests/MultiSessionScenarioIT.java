package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.GlobalEvents;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A feed with two sessions of different interests, a prematch-only and a live-only one, the split
 * 0.0.x allows besides the priority one. Each receives the messages of its own producer only, and
 * each producer's recovery waits for the snapshot complete of the session it is in scope for, not
 * for the other's.
 */
class MultiSessionScenarioIT {

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final String LIVE_RECOVERY = "/v1/live/recovery/initiate_request";

    /** An odds change of the live producer, 2, carrying no request id, so not a recovery's. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), " request_id=\"2049987833\"", "");

    /** The same odds change from the prematch producer, 1. */
    private static final String PREMATCH_ODDS_CHANGE = FeedMessages.fromProducer(LIVE_ODDS_CHANGE, 2, 1);

    /** How long a session is watched for a message that should not reach it. */
    private static final Duration NOTHING_MORE = Duration.ofSeconds(2);

    /**
     * Each session receives the messages of the producer its interest covers, and not those of the
     * other: the broker routes them by the prematch or live part of the routing key.
     */
    @Test
    void aPrematchAndALiveSessionEachReceiveTheirOwnProducersMessages() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            var prematch = new Received();
            var live = new Received();
            open(sdk, prematch, live);

            assertThat(feed.publish(PREMATCH_ODDS_CHANGE))
                    .as("the prematch odds change routed")
                    .isTrue();
            assertThat(feed.publish(LIVE_ODDS_CHANGE))
                    .as("the live odds change routed")
                    .isTrue();

            assertThat(prematch.next(OddsChange.class).getProducer().getId())
                    .as("the producer of what the prematch session receives")
                    .isEqualTo(1);
            assertThat(live.next(OddsChange.class).getProducer().getId())
                    .as("the producer of what the live session receives")
                    .isEqualTo(2);
            assertThat(prematch.poll(Message.class, NOTHING_MORE))
                    .as("what else the prematch session receives")
                    .isEmpty();
            assertThat(live.poll(Message.class, Duration.ZERO))
                    .as("what else the live session receives")
                    .isEmpty();
        }
    }

    /**
     * Both producers are asked for a recovery, and each recovery's snapshot complete reaches both
     * sessions. With the live session still in a callback, the prematch session's copy brings the
     * prematch producer up on its own; the live producer waits for the live session to take its
     * copy, and comes up once it has.
     */
    @Test
    void eachProducerComesUpOnTheSnapshotCompleteOfTheSessionItIsInScopeFor() throws InterruptedException {
        var live = new HoldingTheFirstOddsChange();
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            try {
                var prematch = new Received();
                open(sdk, prematch, live);
                feed.publish(alive(1, true));
                feed.publish(alive(2, true));
                long prematchRequest = requestId(rest.awaitRequest("POST", PREMATCH_RECOVERY));
                long liveRequest = requestId(rest.awaitRequest("POST", LIVE_RECOVERY));

                // the live session in its callback, with whatever comes next queued behind it
                assertThat(feed.publish(LIVE_ODDS_CHANGE))
                        .as("the live odds change routed")
                        .isTrue();
                assertThat(live.held.await(Received.DELIVERY.toSeconds(), TimeUnit.SECONDS))
                        .as("the live session in the callback of its odds change")
                        .isTrue();
                feed.publish(snapshotComplete(1, prematchRequest));
                feed.publish(snapshotComplete(2, liveRequest));

                GlobalEvents events = sdk.events();
                assertThat(events.nextProducerStatus(1).isDown())
                        .as("the prematch producer down, on the prematch session's snapshot complete")
                        .isFalse();
                Thread.sleep(NOTHING_MORE);
                assertThat(statusesOf(events, 2))
                        .as("status changes of the live producer, with the live session in a callback")
                        .isEmpty();
                var producers = sdk.oddsFeed().getProducerManager();
                assertThat(producers.isProducerDown(2))
                        .as("the live producer down, its session not yet past the snapshot complete")
                        .isTrue();

                live.release.countDown();
                assertThat(events.nextProducerStatus(2).isDown())
                        .as("the live producer down, once the live session took its snapshot complete")
                        .isFalse();
                assertThat(producers.isProducerDown(1))
                        .as("the prematch producer down")
                        .isFalse();
                assertThat(producers.isProducerDown(2))
                        .as("the live producer down")
                        .isFalse();
                assertThat(statusesOf(events, 1))
                        .as("status changes of the prematch producer")
                        .hasSize(1);
            } finally {
                live.release.countDown();
            }
        }
    }

    /** A prematch-only and a live-only session, built in that order, and the feed opened. */
    private static void open(Sdk sdk, OddsFeedListener prematch, OddsFeedListener live) {
        var feed = sdk.oddsFeed();
        feed.getSessionBuilder()
                .setListener(prematch)
                .setMessageInterest(MessageInterest.PREMATCH_ONLY)
                .build();
        feed.getSessionBuilder()
                .setListener(live)
                .setMessageInterest(MessageInterest.LIVE_ONLY)
                .build();
        feed.open();
    }

    private static List<ProducerStatus> statusesOf(GlobalEvents events, long producerId) {
        return events.producerStatuses().stream()
                .filter(status ->
                        status.getProducer() != null && status.getProducer().getId() == producerId)
                .toList();
    }

    private static long requestId(RecordedRequest recovery) {
        var requestId = recovery.parameter("request_id");
        assertThat(requestId).as("request id of " + recovery.path()).isNotBlank();
        return Long.parseLong(requestId);
    }

    /** A session listener that holds its first odds change in the callback until released. */
    private static final class HoldingTheFirstOddsChange implements OddsFeedListener {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            if (!first.compareAndSet(true, false)) {
                return;
            }
            held.countDown();
            try {
                release.await(Received.DELIVERY.toSeconds() * 3, TimeUnit.SECONDS);
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
}
