package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.alive;
import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.snapshotComplete;
import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.FeedMessages;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A producer going down and coming back, and what the SDK does with producers it does not know.
 * {@link ProducerStatusScenarioIT} has the first recovery and the first down; this picks up from
 * there.
 */
class ProducerRecoveryScenarioIT {

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

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
            feed.publish(alive(1, true));
            feed.publish(snapshotComplete(
                    1,
                    requestId(rest.awaitRequests("POST", PREMATCH_RECOVERY, 1).getFirst())));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the first recovery")
                    .isFalse();

            // stamped a second apart, so the recovery point says which of the two alives it came from
            long lastAliveWhileUp = System.currentTimeMillis() - 2_000;
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
     * Every producer starts down, and 0.0.x reports a status change only when the down flag or its
     * reason changes: an alive saying a producer that is still down is unsubscribed changes neither,
     * so the client hears nothing, while the SDK does ask for a recovery.
     */
    @Test
    void anUnsubscribedAliveForAProducerStillDownIsNotReported() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, false));

            KnownDifference.ALREADY_DOWN_PRODUCER_REPORTS_NOTHING.expectLegacy(() -> {
                assertThat(rest.awaitRequest("POST", PREMATCH_RECOVERY))
                        .as("a recovery of producer 1")
                        .isNotNull();
                assertThat(sdk.events().pollProducerStatus(1, Duration.ofSeconds(1)))
                        .as("a status change of producer 1")
                        .isEmpty();
                assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                        .as("producer 1 down")
                        .isTrue();
            });
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
                    () -> {
                        Producer unknown;
                        try {
                            unknown = producers.getProducer(7);
                        } catch (OddsFeedSdkException e) {
                            return; // an error is what the design asks for
                        }
                        assertThat(unknown)
                                .as("producer 7, which the producer list does not have")
                                .isNull();
                    });
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
            KnownDifference.PIPE_SEPARATED_LISTS_ARE_NOT_SPLIT.expectLegacy(() -> assertThat(live.getProducerScopes())
                    .as("scopes of producer 2, listed as live|prematch")
                    .isEmpty());
        }
    }

    private static long requestId(RecordedRequest recovery) {
        var requestId = recovery.parameter("request_id");
        assertThat(requestId).as("request id of " + recovery.path()).isNotBlank();
        return Long.parseLong(requestId);
    }
}
