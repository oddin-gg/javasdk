package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.alive;
import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
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

    private static String withHomeScore(int score) {
        return Fixtures.replace(Fixtures.read(WITH_STATUS), "home_score=\"3\"", "home_score=\"" + score + "\"");
    }
}
