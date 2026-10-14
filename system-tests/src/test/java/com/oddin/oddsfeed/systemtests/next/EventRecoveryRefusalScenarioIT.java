package com.oddin.oddsfeed.systemtests.next;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.NextOnly;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus.State;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * What 1.0 reports of an event recovery the API refused, beyond the null the call returns (see
 * {@code RecoveryRefusalScenarioIT}): the recovery's status, and the feed's health.
 */
@NextOnly
class EventRecoveryRefusalScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String LIVE_ODDS_RECOVERY = "/v1/live/odds/events/" + MATCH + "/initiate_request";
    /** The live producer the event recovery goes to. */
    private static final int LIVE = 2;

    /**
     * The refused recovery was sent with a request id the caller never got, so only the SDK knows
     * it: its status is failed, with the API's refusal as the reason, and the feed counts it. The
     * feed itself stays healthy: a refusal is the API's answer, not a fault. A recovery the API
     * takes afterwards is pending until its snapshot completes, and then completed.
     */
    @Test
    void aRefusedEventRecoveryFailsAndIsCountedWhileTheFeedStaysHealthy() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            rest.refuseEventOddsRecovery("live", MATCH.toString(), 404, 1);
            sdk.open(MessageInterest.ALL);
            var recovery = sdk.oddsFeed().getRecoveryManager();

            assertThat(recovery.initiateEventOddsMessagesRecovery(LIVE, MATCH))
                    .as("the request id of the refused event recovery")
                    .isNull();

            long refusedId =
                    Long.parseLong(rest.awaitRequest("POST", LIVE_ODDS_RECOVERY).parameter("request_id"));
            assertThat(recovery.getEventRecoveryStatus(refusedId))
                    .as("the status of the refused event recovery")
                    .isNotNull()
                    .satisfies(status -> {
                        assertThat(status.state()).as("state").isEqualTo(State.FAILED);
                        assertThat(status.producerId()).as("producer").isEqualTo(LIVE);
                        assertThat(status.eventId()).as("event").isEqualTo(MATCH);
                        assertThat(status.endedAt()).as("ended").isNotNull();
                        assertThat(status.reason()).as("reason").contains("the API did not accept it");
                    });
            var health = sdk.oddsFeed().getHealth();
            assertThat(health.state()).as("the feed's health").isEqualTo(HealthState.HEALTHY);
            assertThat(health.counters())
                    .as("event recoveries counted")
                    .containsEntry("recovery.event_requested", 1L)
                    .containsEntry("recovery.event_refused", 1L);

            Long accepted = recovery.initiateEventOddsMessagesRecovery(LIVE, MATCH);
            assertThat(accepted).as("the request id of the next event recovery").isNotNull();
            assertThat(accepted).as("a new request id").isNotEqualTo(refusedId);
            assertThat(state(recovery.getEventRecoveryStatus(accepted)))
                    .as("the accepted recovery, before its snapshot completes")
                    .isEqualTo(State.PENDING);

            feed.publish(alive(LIVE, true));
            long producerRequest = Long.parseLong(rest.awaitRequest("POST", "/v1/live/recovery/initiate_request")
                    .parameter("request_id"));
            feed.publish(snapshotComplete(LIVE, producerRequest));
            assertThat(sdk.events().nextProducerStatus(LIVE).getProducerStatusReason())
                    .as("the producer's own recovery")
                    .isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
            feed.publish(snapshotComplete(LIVE, accepted));
            assertThat(sdk.events().pollEventRecovery(Duration.ofSeconds(10)))
                    .as("onEventRecoveryCompleted for request " + accepted)
                    .isPresent();
            assertThat(state(recovery.getEventRecoveryStatus(accepted)))
                    .as("the accepted recovery, once its snapshot completed")
                    .isEqualTo(State.COMPLETED);
            assertThat(sdk.oddsFeed().getHealth().counters())
                    .as("only the first was refused")
                    .containsEntry("recovery.event_refused", 1L);
        }
    }

    private static State state(EventRecoveryStatus status) {
        assertThat(status).as("a status").isNotNull();
        return status.state();
    }
}
