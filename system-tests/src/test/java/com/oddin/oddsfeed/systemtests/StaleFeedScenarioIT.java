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
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A feed that is behind: messages stamped well before they arrive, or older than one already
 * handled. Every one is still delivered, with the time the feed stamped it; what changes is
 * whether the live state it carries replaces what the SDK already knows.
 *
 * <p>The messages carry a match status with a home score the REST summary does not have (it says
 * 3), so the score read afterwards tells which message, if any, wrote it.
 */
class StaleFeedScenarioIT {

    private static final String WITH_STATUS = "feed/odds_change/odds_change_closed_with_winner.xml";

    /** An odds change of producer 1, live rather than a recovery's: it carries no request id. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            FeedMessages.fromProducer(Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), 2, 1),
            " request_id=\"2049987833\"",
            "");

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

    /**
     * The safety net's limit and window on 1.0, set so the scenario takes seconds. A message a
     * minute behind is past this limit and short of the two minutes the net takes unless set, so
     * only the options set make it act.
     */
    private static final Duration STALE_LIMIT = Duration.ofSeconds(30);

    private static final Duration STALE_WINDOW = Duration.ofSeconds(1);
    private static final Duration BEHIND = Duration.ofMinutes(1);

    /**
     * An odds change stamped half an hour ago arrives as sent. 0.0.x writes its score into the
     * match status as if it were current; 1.0 treats a message that old as backlog - REST has taken
     * over since - and leaves the status to the summary.
     */
    @Test
    void aMessageFromHalfAnHourAgoIsStillDelivered() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            // an alive of its own, so the SDK knows the live producer's clock
            feed.publish(alive(2, true));
            long sentAt = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
            feed.publishAsIs(stampedAt(withHomeScore(7), sentAt));

            OddsChange<?> oddsChange = received.next(OddsChange.class);
            assertThat(oddsChange.getTimestamp().getCreated()).as("created").isEqualTo(sentAt);
            var match = (Match) oddsChange.getEvent();
            KnownDifference.STALE_MESSAGE_WRITES_THE_STATUS.expect(
                    () -> assertThat(match.getStatus().getHomeScore())
                            .as("home score, from the old message")
                            .isEqualTo(7.0),
                    () -> assertThat(match.getStatus().getHomeScore())
                            .as("home score, from the summary")
                            .isEqualTo(3.0));
        }
    }

    /**
     * Two odds changes arrive newest first. Both are delivered, in the order they came. 0.0.x lets
     * the older one's score replace the newer one's; 1.0 keeps the newer, since the older is out of
     * date by its own timestamp.
     */
    @Test
    void anOlderMessageDoesNotReplaceTheStatusOfANewerOne() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            long newer = System.currentTimeMillis() - 1_000;
            long older = newer - 5_000;
            feed.publishAsIs(stampedAt(withHomeScore(2), newer));
            feed.publishAsIs(stampedAt(withHomeScore(1), older));

            assertThat(received.next(OddsChange.class).getTimestamp().getCreated())
                    .as("first")
                    .isEqualTo(newer);
            OddsChange<?> second = received.next(OddsChange.class);
            assertThat(second.getTimestamp().getCreated()).as("second").isEqualTo(older);
            var match = (Match) second.getEvent();
            KnownDifference.OLDER_MESSAGE_OVERWRITES_THE_STATUS.expect(
                    () -> assertThat(match.getStatus().getHomeScore())
                            .as("home score, from the older message")
                            .isEqualTo(1.0),
                    () -> assertThat(match.getStatus().getHomeScore())
                            .as("home score, from the newer message")
                            .isEqualTo(2.0));
        }
    }

    /**
     * A session falls far behind: producer 1's live messages arrive a minute after the feed stamped
     * them, for three of the safety net's windows, after an alive on time. 0.0.x has no
     * safety net and processes them one by one, asking for nothing; 1.0's asks for a recovery of the
     * session's producer and, once the API has accepted it, replaces the session's channel, which
     * comes with a new queue. On 1.0 the scenario sets the net's limit and window; 0.0.x has neither.
     */
    @Test
    void aSessionFarBehindHasItsChannelReplaced() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed, StaleFeedScenarioIT::safetyNetInSeconds)) {
            Received received = sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, true));
            RecordedRequest first = rest.awaitRequest("POST", PREMATCH_RECOVERY);
            feed.publish(snapshotComplete(1, Long.parseLong(first.parameter("request_id"))));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("producer 1 down once its first recovery completed")
                    .isFalse();
            List<String> queues = feed.sessionQueues();
            assertThat(queues).as("the session's queue").hasSize(1);

            // an alive on time, so the SDK knows the producer's clock; none in the stream, since the
            // session would take each as on time, while one queued behind a backlog is as old as it
            feed.publish(alive(1, true));
            long until = System.nanoTime() + STALE_WINDOW.multipliedBy(3).toNanos();
            int published = 0;
            while (System.nanoTime() < until) {
                feed.publishAsIs(stampedAt(LIVE_ODDS_CHANGE, System.currentTimeMillis() - BEHIND.toMillis()));
                published++;
                Thread.sleep(100);
            }
            int sent = published;
            KnownDifference.FAR_BEHIND_FEED_IS_NOT_RESET.expect(
                    () -> {
                        for (int i = 0; i < sent; i++) {
                            received.next(OddsChange.class);
                        }
                        assertThat(rest.requests("POST", PREMATCH_RECOVERY))
                                .as("recoveries asked for, once every message is processed")
                                .hasSize(1);
                        assertThat(feed.sessionQueues())
                                .as("the session's queue")
                                .isEqualTo(queues);
                    },
                    () -> {
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
                    });
        }
    }

    /**
     * On 1.0, the safety net's limit and window by reflection: 0.0.x has neither setter, and this
     * class compiles against both.
     */
    private static OddsFeedConfigurationBuilder safetyNetInSeconds(OddsFeedConfigurationBuilder builder) {
        if (KnownDifference.lineUnderTest() == KnownDifference.Line.NEXT) {
            set(builder, "setStaleMessageLimit", STALE_LIMIT);
            set(builder, "setStaleMessageWindow", STALE_WINDOW);
        }
        return builder;
    }

    private static void set(OddsFeedConfigurationBuilder builder, String setter, Duration value) {
        try {
            builder.getClass().getMethod(setter, Duration.class).invoke(builder, value);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("the 1.0 builder's " + setter, e);
        }
    }

    private static String withHomeScore(int score) {
        return Fixtures.replace(Fixtures.read(WITH_STATUS), "home_score=\"3\"", "home_score=\"" + score + "\"");
    }
}
