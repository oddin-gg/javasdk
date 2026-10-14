package com.oddin.oddsfeed.systemtests.next;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static com.oddin.oddsfeed.fakes.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.support.NextOnly;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 1.0 safety net reads the age of a message by the producer's clock, corrected by the offset
 * measured on its alives, which 0.0.x has no net to do. A producer whose clock is a minute behind
 * the SDK's, with the net set to act on messages over 30 seconds old, is not a feed far behind: its
 * messages are on time by its own clock. The same stamps from a producer whose clock agrees are
 * old, and the net acts; {@code StaleFeedScenarioIT} has that on its own.
 */
@NextOnly
class ClockSkewSafetyNetScenarioIT {

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final Duration LIMIT = Duration.ofSeconds(30);
    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final Duration BEHIND = Duration.ofMinutes(1);

    /** A live odds change of producer 1, carrying no request id, so not a recovery's. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            FeedMessages.fromProducer(Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), 2, 1),
            " request_id=\"2049987833\"",
            "");

    /**
     * Producer 1's clock is a minute behind: its alive and its messages say so, for three windows
     * of the net. Nothing is asked for and the session keeps its queue. Then the producer's clock
     * agrees again, with an alive that says so, and messages stamped a minute back are old: the net
     * asks for a recovery and the session gets a new queue. So the first half is no accident of a
     * net that did not look.
     */
    @Test
    void aProducerWhoseClockIsBehindIsNotAFeedFarBehind() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(
                        rest,
                        feed,
                        builder -> builder.setStaleMessageLimit(LIMIT).setStaleMessageWindow(WINDOW))) {
            Received received = sdk.open(MessageInterest.ALL);
            feed.skewClock(BEHIND.negated());
            feed.publish(alive(1, true));
            var first = rest.awaitRequests("POST", PREMATCH_RECOVERY, 1).getFirst();
            feed.publish(snapshotComplete(1, Long.parseLong(first.parameter("request_id"))));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("producer 1 down once its first recovery completed")
                    .isFalse();
            List<String> queues = feed.sessionQueues();
            assertThat(queues).as("the session's queue").hasSize(1);

            // an alive stamped by the producer's clock, so the SDK has its offset; none in the stream
            feed.publish(alive(1, true));
            int published = stream(() -> feed.publish(LIVE_ODDS_CHANGE));
            for (int i = 0; i < published; i++) {
                received.next(OddsChange.class);
            }
            // the net looks at the end of each second; give it two more
            Thread.sleep(WINDOW.multipliedBy(2).toMillis());
            assertThat(rest.requests("POST", PREMATCH_RECOVERY))
                    .as("recoveries asked for: the first only, the producer's age is not that of its stamp")
                    .hasSize(1);
            assertThat(feed.sessionQueues()).as("the session's queue").isEqualTo(queues);
            assertThat(sdk.events().pollProducerStatus(1, Duration.ofMillis(100)))
                    .as("a status change of producer 1")
                    .isEmpty();

            // the same stamps, the producer's clock now the SDK's: a feed a minute behind
            feed.skewClock(Duration.ZERO);
            feed.publish(alive(1, true));
            stream(() -> feed.publishAsIs(stampedAt(LIVE_ODDS_CHANGE, System.currentTimeMillis() - BEHIND.toMillis())));
            assertThat(rest.awaitRequests("POST", PREMATCH_RECOVERY, 2))
                    .as("recoveries asked for: the first, and the safety net's")
                    .hasSizeGreaterThanOrEqualTo(2);
            long deadline = System.nanoTime() + Received.DELIVERY.toNanos();
            while (feed.sessionQueues().equals(queues) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(feed.sessionQueues())
                    .as("the session's queue, once the safety net replaced its channel")
                    .hasSize(1)
                    .doesNotContainAnyElementsOf(queues);
        }
    }

    /** Publishes, a tenth of a second apart, for three windows; returns how many. */
    private static int stream(Runnable publish) throws InterruptedException {
        long until = System.nanoTime() + WINDOW.multipliedBy(3).toNanos();
        int published = 0;
        while (System.nanoTime() < until) {
            publish.run();
            published++;
            Thread.sleep(100);
        }
        return published;
    }
}
