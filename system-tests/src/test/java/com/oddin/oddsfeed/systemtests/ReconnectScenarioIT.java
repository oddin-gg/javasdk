package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static com.oddin.oddsfeed.fakes.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The connection to the feed breaking and coming back while the SDK runs. Whatever the broker
 * held for the old connection's queue is gone with it; messages flow again on the new one, and a
 * recovery is what fills the gap.
 *
 * <p>Kept short on purpose: 0.0.x starts watching producers for missing alives a minute after
 * {@code open()}, and a longer scenario would see that watchdog take the producer down on top of
 * what the reconnect does (KD-7 in KNOWN-DIFFERENCES.md).
 */
class ReconnectScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

    /**
     * The connection drops and comes back: the SDK reports it down, logs in again, and messages
     * reach the listener again. A producer that was up before the drop has missed whatever was sent
     * meanwhile; 1.0 asks for a recovery for it, 0.0.x does not and keeps it up.
     */
    @Test
    void afterAReconnectMessagesFlowAgainAndTheGapIsRecovered() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, true));
            feed.publish(snapshotComplete(
                    1, requestId(rest.awaitRequest("POST", PREMATCH_RECOVERY).parameter("request_id"))));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the first recovery")
                    .isFalse();
            int loginsBefore = feed.logins().size();

            feed.pause();
            assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(30)))
                    .as("the SDK reports the connection down while the broker is paused")
                    .isTrue();
            feed.resume();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (feed.logins().size() == loginsBefore && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(feed.logins())
                    .as("the SDK logs in again once the broker is back")
                    .hasSizeGreaterThan(loginsBefore);

            // until the broker notices the old connection is gone its queue still takes messages, so
            // "routed" proves nothing; keep publishing until one arrives
            Optional<?> afterReconnect = Optional.empty();
            deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (afterReconnect.isEmpty() && System.nanoTime() < deadline) {
                feed.publishFixture(ODDS_CHANGE);
                afterReconnect = received.poll(OddsChange.class, Duration.ofSeconds(1));
            }
            assertThat(afterReconnect).as("an odds change after the reconnect").isPresent();

            KnownDifference.NO_RECOVERY_AFTER_A_RECONNECT.expect(
                    () -> {
                        // 0.0.x takes the recovery point of a producer that is up from its last alive, after
                        // deciding on a recovery; once it shows this alive's time, the alive has been handled
                        long aliveAt = System.currentTimeMillis();
                        feed.publishAsIs(stampedAt(alive(1, true), aliveAt));
                        var producers = sdk.oddsFeed().getProducerManager();
                        long handled = System.nanoTime() + Received.DELIVERY.toNanos();
                        while (!Instant.ofEpochMilli(aliveAt)
                                        .equals(producers.getProducer(1).getTimestampForRecovery())
                                && System.nanoTime() < handled) {
                            Thread.sleep(50);
                        }
                        assertThat(producers.getProducer(1).getTimestampForRecovery())
                                .as("recovery point of producer 1, from the alive after the reconnect")
                                .isEqualTo(Instant.ofEpochMilli(aliveAt));
                        assertThat(rest.requests("POST", PREMATCH_RECOVERY))
                                .as("recovery requests of producer 1")
                                .hasSize(1);
                        assertThat(producers.isProducerDown(1))
                                .as("producer 1 down")
                                .isFalse();
                    },
                    () -> {
                        feed.publish(alive(1, true));
                        assertThat(rest.awaitRequests("POST", PREMATCH_RECOVERY, 2))
                                .as("recovery requests of producer 1")
                                .hasSizeGreaterThanOrEqualTo(2);
                    });
        }
    }

    /**
     * The feed closes its own connection. 0.0.x reports that as the connection down, as it reports
     * a lost one; 1.0 reports a loss only, since a client alerting on it would alert on every
     * shutdown.
     */
    @Test
    @SuppressWarnings("try") // closed inside the block, to see what closing reports
    void closingTheFeedIsNotReportedAsTheConnectionDown() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            sdk.close();
            KnownDifference.CLOSE_IS_REPORTED_AS_CONNECTION_DOWN.expect(
                    () -> assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(10)))
                            .as("connection down on close")
                            .isTrue(),
                    () -> assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(2)))
                            .as("connection down on close")
                            .isFalse());
        }
    }

    private static long requestId(String requestId) {
        assertThat(requestId).as("request id of the recovery").isNotBlank();
        return Long.parseLong(requestId);
    }
}
