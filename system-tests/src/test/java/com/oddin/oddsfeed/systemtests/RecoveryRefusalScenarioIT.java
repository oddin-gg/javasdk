package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The API refusing a recovery request with an answer of its own - a 4xx for this producer or this
 * event - while everything else works. {@link RestOutageScenarioIT} has the whole API down.
 */
class RecoveryRefusalScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String LIVE_ODDS_RECOVERY = "/v1/live/odds/events/" + MATCH + "/initiate_request";
    private static final String LIVE_STATEFUL_RECOVERY =
            "/v1/live/stateful_messages/events/" + MATCH + "/initiate_request";
    /** The live producer the event recoveries go to. */
    private static final int LIVE = 2;

    /**
     * A producer's first recovery refused twice, with a 409 the HTTP client does not retry: the
     * producer is down until a recovery is accepted and its snapshot completes. 0.0.x marks the
     * recovery started and asks again only five minutes after the request (KD-11), so it has
     * asked once when 1.0 has asked three times, each with a new request id, after a backoff.
     */
    @Test
    void aProducersRecoveryRefusedTwiceIsAskedForAgainAndTheProducerComesUpOnceItIsAccepted()
            throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            rest.refuseRecovery("pre", 409, 2);
            sdk.open(MessageInterest.ALL);
            var producers = sdk.oddsFeed().getProducerManager();
            feed.publish(alive(1, true));
            String refused = rest.awaitRequest("POST", PREMATCH_RECOVERY).parameter("request_id");
            assertThat(refused).as("request id of the refused recovery").isNotBlank();

            KnownDifference.FAILED_RECOVERY_IS_NOT_RETRIED.expect(
                    () -> {
                        assertThat(requestsWhileAlive(rest, feed, 2, Duration.ofSeconds(3)))
                                .as("recovery requests of producer 1, with an alive every second")
                                .hasSize(1);
                        assertThat(producers.isProducerDown(1))
                                .as("producer 1 down, its recovery refused")
                                .isTrue();
                    },
                    () -> {
                        // the backoff is the recovery actor's to choose: five seconds, then ten
                        var requests = requestsWhileAlive(rest, feed, 3, Duration.ofSeconds(40));
                        assertThat(requests)
                                .as("recovery requests of producer 1, with an alive every second")
                                .hasSize(3);
                        assertThat(requests)
                                .extracting(request -> request.parameter("request_id"))
                                .as("request ids: a new one for each")
                                .doesNotHaveDuplicates()
                                .startsWith(refused);
                        assertThat(rest.recoveriesRefused())
                                .as("recoveries refused")
                                .isEqualTo(2);
                        assertThat(producers.isProducerDown(1))
                                .as("producer 1 down until the accepted recovery completes")
                                .isTrue();
                        assertThat(sdk.events().producerStatuses())
                                .as("status changes while the recoveries were refused")
                                .isEmpty();

                        feed.publish(snapshotComplete(
                                1, Long.parseLong(requests.get(2).parameter("request_id"))));
                        var up = sdk.events().nextProducerStatus(1);
                        assertThat(up.isDown())
                                .as("down after the accepted recovery")
                                .isFalse();
                        assertThat(up.getProducerStatusReason())
                                .as("reason")
                                .isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
                        assertThat(producers.isProducerDown(1))
                                .as("producer 1 down")
                                .isFalse();
                    });
        }
    }

    /**
     * An event's odds recovery the API refuses: the call reports it by returning null, rather than
     * a request id, and nothing is left to wait for. The feed carries on - the producer's own
     * recovery and what the session receives are not affected - and a recovery of the same event
     * asked for again, once the API takes it, gets its request id.
     */
    @Test
    void anEventOddsRecoveryTheApiRefusedReturnsNullAndTheFeedCarriesOn() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            rest.refuseEventOddsRecovery("live", MATCH.toString(), 404, 1);
            var received = sdk.open(MessageInterest.ALL);
            var recovery = sdk.oddsFeed().getRecoveryManager();

            assertThat(recovery.initiateEventOddsMessagesRecovery(LIVE, MATCH))
                    .as("the request id of the refused event recovery")
                    .isNull();
            assertThat(rest.requests("POST", LIVE_ODDS_RECOVERY))
                    .as("requests of the refused event recovery: refused once, not retried")
                    .hasSize(1);

            carriesOn(rest, feed, sdk, received);
            Long accepted = recovery.initiateEventOddsMessagesRecovery(LIVE, MATCH);
            assertThat(accepted).as("the request id of the next event recovery").isNotNull();
            assertThat(rest.requests("POST", LIVE_ODDS_RECOVERY))
                    .extracting(request -> request.parameter("request_id"))
                    .as("request ids sent")
                    .hasSize(2)
                    .endsWith(accepted.toString());
        }
    }

    /** The same for the event's stateful recovery, which has a path of its own. */
    @Test
    void anEventStatefulRecoveryTheApiRefusedReturnsNullAndTheFeedCarriesOn() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            rest.refuseEventStatefulRecovery("live", MATCH.toString(), 404, 1);
            var received = sdk.open(MessageInterest.ALL);
            var recovery = sdk.oddsFeed().getRecoveryManager();

            assertThat(recovery.initiateEventStatefulMessagesRecovery(LIVE, MATCH))
                    .as("the request id of the refused event recovery")
                    .isNull();
            assertThat(rest.requests("POST", LIVE_STATEFUL_RECOVERY))
                    .as("requests of the refused event recovery: refused once, not retried")
                    .hasSize(1);

            carriesOn(rest, feed, sdk, received);
            assertThat(recovery.initiateEventStatefulMessagesRecovery(LIVE, MATCH))
                    .as("the request id of the next event recovery")
                    .isNotNull();
        }
    }

    /** What an event recovery's refusal leaves alone: a producer's recovery and the session's messages. */
    private static void carriesOn(FakeRestServer rest, FakeFeed feed, Sdk sdk, Received received)
            throws InterruptedException {
        feed.publish(alive(LIVE, true));
        String requestId =
                rest.awaitRequest("POST", "/v1/live/recovery/initiate_request").parameter("request_id");
        feed.publish(snapshotComplete(LIVE, Long.parseLong(requestId)));
        var up = sdk.events().nextProducerStatus(LIVE);
        assertThat(up.isDown())
                .as("producer 2 down, once its own recovery completed")
                .isFalse();
        assertThat(up.getProducerStatusReason()).isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);

        feed.publishFixture(ODDS_CHANGE);
        assertThat(received.next(OddsChange.class))
                .as("an odds change after the refusal")
                .isNotNull();
    }

    /**
     * The recovery requests of producer 1 there are once {@code count} have arrived, sending it an
     * alive every second for up to {@code wait}; fewer when they do not.
     */
    private static List<RecordedRequest> requestsWhileAlive(
            FakeRestServer rest, FakeFeed feed, int count, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (rest.requests("POST", PREMATCH_RECOVERY).size() < count && System.nanoTime() < deadline) {
            feed.publish(alive(1, true));
            Thread.sleep(Duration.ofSeconds(1));
        }
        return rest.requests("POST", PREMATCH_RECOVERY);
    }
}
