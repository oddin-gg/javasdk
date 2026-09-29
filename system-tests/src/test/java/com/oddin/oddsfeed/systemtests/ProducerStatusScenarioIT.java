package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.support.GlobalEvents;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The system messages, alive and snapshot complete, through what they drive. Neither has a
 * listener callback of its own; they change producer status, which the global events listener
 * hears about.
 *
 * <p>The SDK starts every producer down. An alive saying the producer is subscribed makes it ask
 * for a recovery snapshot, and the snapshot complete carrying that request's id brings the
 * producer up. An alive saying it is no longer subscribed takes it down again.
 */
class ProducerStatusScenarioIT {

    /** Producer 1, subscribed. */
    private static final String ALIVE = "feed/alive/alive.xml";
    /** Producer 1, request 712. */
    private static final String SNAPSHOT_COMPLETE = "feed/snapshot_complete/snapshot_complete.xml";

    private static final URN MATCH = URN.parse("od:match:198314");

    @Test
    void anAliveStartsARecoveryAndItsSnapshotCompleteBringsTheProducerUp() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            ProducerManager producers = sdk.oddsFeed().getProducerManager();
            assertThat(producers.isProducerDown(1))
                    .as("producer 1 down at first")
                    .isTrue();

            feed.publishFixture(ALIVE);
            long requestId = recoveryRequested(rest, "pre");
            long from = System.currentTimeMillis();
            feed.publish(snapshotComplete(1, requestId));

            ProducerStatus status = sdk.events().nextProducerStatus(1);
            assertThat(status.getProducerStatusReason())
                    .as("reason")
                    .isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
            assertThat(status.isDown()).as("down").isFalse();
            assertThat(status.isDelayed()).as("delayed, just after an alive").isFalse();
            assertThat(status.getTimestamp().getCreated()).as("created").isBetween(from, System.currentTimeMillis());
            assertThat(producers.isProducerDown(1))
                    .as("producer 1 down after recovery")
                    .isFalse();
            assertThat(producers.getProducer(1).getRecoveryInfo().getRequestId())
                    .as("the last recovery of producer 1")
                    .isEqualTo(requestId);
        }
    }

    @Test
    void anAliveSayingTheProducerIsNoLongerSubscribedTakesItDown() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            // a producer that is down already has no status to change, so bring it up first
            feed.publishFixture(ALIVE);
            feed.publish(snapshotComplete(1, recoveryRequested(rest, "pre")));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the first recovery")
                    .isFalse();

            long from = System.currentTimeMillis();
            feed.publish(Fixtures.read(ALIVE).replace("subscribed=\"1\"", "subscribed=\"0\""));

            ProducerStatus status = sdk.events().nextProducerStatus(1);
            assertThat(status.getProducerStatusReason()).as("reason").isEqualTo(ProducerStatusReason.OTHER);
            assertThat(status.isDown()).as("down").isTrue();
            assertThat(status.getTimestamp().getCreated()).as("created").isBetween(from, System.currentTimeMillis());
            assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                    .as("producer 1 down")
                    .isTrue();
        }
    }

    /**
     * The snapshot complete of an event recovery completes it: the request goes out with the id the
     * SDK returns, and the snapshot complete carrying that id is reported through
     * {@code onEventRecoveryCompleted}. 0.0.x never reports it (KD-1): for a producer with a live
     * scope, like producer 2 here, it counts an event recovery complete once a live-only session has
     * seen its snapshot complete - yet it only counts snapshot completes on sessions that are
     * neither live-only nor prematch-only, so that never happens.
     *
     * <p>A completed producer recovery after it shows that the SDK has consumed the event's
     * snapshot complete, which went into the same queue first, so silence is not a message still on
     * its way.
     */
    @Test
    void theSnapshotCompleteOfAnEventRecoveryCompletesIt() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            Long eventRequestId = sdk.oddsFeed().getRecoveryManager().initiateEventOddsMessagesRecovery(2, MATCH);
            assertThat(eventRequestId).as("the event recovery's request id").isNotNull();
            assertThat(rest.awaitRequest("POST", "/v1/live/odds/events/" + MATCH + "/initiate_request")
                            .parameter("request_id"))
                    .as("the request id sent")
                    .isEqualTo(eventRequestId.toString());
            feed.publish(Fixtures.read(ALIVE).replace("product=\"1\"", "product=\"2\""));
            long producerRequestId = recoveryRequested(rest, "live");

            feed.publish(snapshotComplete(2, eventRequestId));
            feed.publish(snapshotComplete(2, producerRequestId));

            assertThat(sdk.events().nextProducerStatus(2).getProducerStatusReason())
                    .as("producer 2, once both snapshot completes are through")
                    .isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
            KnownDifference.EVENT_RECOVERY_NOT_REPORTED.expect(
                    () -> assertThat(sdk.events().pollEventRecovery(Duration.ofSeconds(1)))
                            .as("onEventRecoveryCompleted for request " + eventRequestId)
                            .isEmpty(),
                    () -> assertThat(sdk.events().pollEventRecovery(Received.DELIVERY))
                            .as("onEventRecoveryCompleted for request " + eventRequestId)
                            .contains(new GlobalEvents.EventRecovery(MATCH, eventRequestId)));
        }
    }

    /** Waits for the snapshot recovery the SDK starts for this producer; returns its request id. */
    private static long recoveryRequested(FakeRestServer rest, String producerName) throws InterruptedException {
        String requestId = rest.awaitRequest("POST", "/v1/" + producerName + "/recovery/initiate_request")
                .parameter("request_id");
        assertThat(requestId)
                .as("request id of the recovery of " + producerName)
                .isNotBlank();
        return Long.parseLong(requestId);
    }

    private static String snapshotComplete(int producer, long requestId) {
        return Fixtures.read(SNAPSHOT_COMPLETE)
                .replace("product=\"1\"", "product=\"" + producer + "\"")
                .replace("request_id=\"712\"", "request_id=\"" + requestId + "\"");
    }
}
