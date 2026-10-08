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
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The connection to the feed, or a session's queue, breaking and coming back while the SDK runs.
 * Whatever the broker held for the old queue is gone with it; messages flow again on the new one,
 * and a recovery is what fills the gap.
 *
 * <p>Kept short on purpose: 0.0.x starts watching producers for missing alives a minute after
 * {@code open()}, and a longer scenario would see that watchdog take the producer down on top of
 * what the reconnect does (KD-7 in KNOWN-DIFFERENCES.md).
 */
class ReconnectScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

    /** A live odds change from producer 1, carrying no request id, so not a recovery's. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            FeedMessages.fromProducer(Fixtures.read(ODDS_CHANGE), 2, 1), " request_id=\"2049987833\"", "");

    /**
     * The connection drops and comes back: the SDK reports it down, logs in again, and messages
     * reach the listener again. A producer that was up before the drop has missed whatever was sent
     * meanwhile; 1.0 takes it down, asks for a recovery from where the session had got to, and brings
     * it back once that is complete; 0.0.x does not and keeps it up.
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
            long processedAt = processedLive(sdk, feed, received);
            int loginsBefore = feed.logins().size();
            List<String> lostQueues = feed.boundQueues();
            assertThat(lostQueues).as("the SDK's queues before the pause").isNotEmpty();

            feed.pause();
            assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(30)))
                    .as("the SDK reports the connection down while the broker is paused")
                    .isTrue();
            feed.resume();
            // the broker takes the lost connection's queues down once it is back, and refuses a message
            // routed to one it has half taken down; publish once they are unbound
            assertThat(feed.awaitUnbound(lostQueues, Duration.ofSeconds(10)))
                    .as("the queues of the lost connection unbound once the broker is back")
                    .isTrue();
            // from here an alive every second, as the producer sends them: a reconnect that takes
            // longer than the alive interval must not read as the producer gone silent before it
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            for (int i = 0; feed.logins().size() == loginsBefore && System.nanoTime() < deadline; i++) {
                if (i % 5 == 0) {
                    feed.publish(alive(1, true));
                }
                Thread.sleep(200);
            }
            assertThat(feed.logins())
                    .as("the SDK logs in again once the broker is back")
                    .hasSizeGreaterThan(loginsBefore);

            // logging in comes before the new queue is bound, so keep publishing until one arrives
            Optional<?> afterReconnect = Optional.empty();
            deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (afterReconnect.isEmpty() && System.nanoTime() < deadline) {
                feed.publish(alive(1, true));
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
                        recoveredFromWhereTheSessionWas(sdk, rest, feed, processedAt);
                    });
        }
    }

    /**
     * The broker deletes the session's queue while the connection stays up, as an operator or a
     * broker policy might, and cancels its consumer. 1.0 opens the session's channel again, with a
     * new queue, takes the producer down and asks for a recovery of what the old one held, from where
     * the session had got to, which brings it back. 0.0.x's AMQP client recovers a
     * lost connection only: the session receives nothing more, and since the SDK's alive-only queue
     * still gets the alives, no recovery is asked for, and the producer stays up until the watchdog
     * runs a minute after {@code open()} (KD-7), past the end of this scenario.
     */
    @Test
    void afterTheBrokerTakesTheSessionsQueueMessagesFlowAgainAndTheGapIsRecovered() throws InterruptedException {
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
            feed.publishFixture(ODDS_CHANGE);
            received.next(OddsChange.class);
            long processedAt = processedLive(sdk, feed, received);
            var queues = feed.sessionQueues();
            assertThat(queues).as("the session's queue").hasSize(1);

            feed.deleteSessionQueues();
            assertThat(feed.sessionQueues())
                    .as("session queues once the session's is deleted")
                    .doesNotContainAnyElementsOf(queues);
            Optional<?> afterLoss = firstOddsChange(feed, received, Received.DELIVERY);

            KnownDifference.LOST_CHANNEL_IS_NOT_OPENED_AGAIN.expect(
                    () -> {
                        assertThat(afterLoss)
                                .as("an odds change after the queue was deleted")
                                .isEmpty();
                        assertThat(feed.sessionQueues())
                                .as("session queues declared again")
                                .isEmpty();
                        // once the recovery point shows this alive's time, the alive has been handled
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
                                .as("recovery point of producer 1, from the last alive")
                                .isEqualTo(Instant.ofEpochMilli(aliveAt));
                        assertThat(rest.requests("POST", PREMATCH_RECOVERY))
                                .as("recovery requests of producer 1")
                                .hasSize(1);
                        assertThat(sdk.events().pollProducerStatus(1, Duration.ofSeconds(1)))
                                .as("a status change of producer 1")
                                .isEmpty();
                        assertThat(producers.isProducerDown(1))
                                .as("producer 1 down")
                                .isFalse();
                        assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(1)))
                                .as("connection down")
                                .isFalse();
                    },
                    () -> {
                        assertThat(afterLoss)
                                .as("an odds change after the queue was deleted")
                                .isPresent();
                        feed.publish(alive(1, true));
                        recoveredFromWhereTheSessionWas(sdk, rest, feed, processedAt);
                    });
        }
    }

    /**
     * A live odds change of producer 1, stamped now, published and processed: the last message the
     * session processes before the loss. On 1.0 it waits until the recovery has taken it, as the
     * producer's recovery timestamp shows, so the loss cannot overtake it.
     *
     * @return its timestamp
     */
    private static long processedLive(Sdk sdk, FakeFeed feed, Received received) throws InterruptedException {
        long processedAt = System.currentTimeMillis();
        feed.publishAsIs(stampedAt(LIVE_ODDS_CHANGE, processedAt));
        received.next(OddsChange.class);
        if (KnownDifference.lineUnderTest() == KnownDifference.Line.NEXT) {
            var producers = sdk.oddsFeed().getProducerManager();
            long deadline = System.nanoTime() + Received.DELIVERY.toNanos();
            while (!Instant.ofEpochMilli(processedAt)
                            .equals(producers.getProducer(1).getTimestampForRecovery())
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(producers.getProducer(1).getTimestampForRecovery())
                    .as("the recovery timestamp once the live odds change is processed")
                    .isEqualTo(Instant.ofEpochMilli(processedAt));
        }
        return processedAt;
    }

    /**
     * On 1.0, after the session's queue lost what it held: producer 1 went down, its second recovery
     * starts at the session's checkpoint, the live message it processed last before the loss, and
     * the snapshot complete of that recovery brings the producer back.
     */
    private static void recoveredFromWhereTheSessionWas(Sdk sdk, FakeRestServer rest, FakeFeed feed, long processedAt)
            throws InterruptedException {
        List<RecordedRequest> recoveries = rest.awaitRequests("POST", PREMATCH_RECOVERY, 2);
        assertThat(recoveries).as("recovery requests of producer 1").hasSizeGreaterThanOrEqualTo(2);
        assertThat(sdk.events().nextProducerStatus(1).isDown())
                .as("producer 1 down once the queue lost what it held")
                .isTrue();
        RecordedRequest second = recoveries.get(1);
        assertThat(second.parameter("after"))
                .as("where the second recovery starts; none would mean a full snapshot")
                .isNotNull();
        assertThat(Long.parseLong(second.parameter("after")))
                .as("where the second recovery starts: the session's checkpoint before the loss")
                .isEqualTo(processedAt);

        feed.publish(snapshotComplete(1, requestId(second.parameter("request_id"))));
        ProducerStatus back = sdk.events().nextProducerStatus(1);
        while (back.isDown()) {
            back = sdk.events().nextProducerStatus(1);
        }
        assertThat(back.getProducerStatusReason())
                .as("why producer 1 is back")
                .isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
        assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                .as("producer 1 down")
                .isFalse();
    }

    /**
     * Publishes odds changes, each after an alive that says producer 1 is there, until one reaches
     * the listener or {@code wait} is over.
     */
    private static Optional<?> firstOddsChange(FakeFeed feed, Received received, Duration wait)
            throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            feed.publish(alive(1, true));
            feed.publishFixture(ODDS_CHANGE);
            var oddsChange = received.poll(OddsChange.class, Duration.ofSeconds(1));
            if (oddsChange.isPresent()) {
                return oddsChange;
            }
        }
        return Optional.empty();
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
