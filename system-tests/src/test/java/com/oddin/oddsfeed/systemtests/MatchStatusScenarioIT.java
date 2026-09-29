package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The match status a client reads after an odds change carried one. Up to 0.0.56 the SDK knew only
 * five of the feed's statuses and reported a cancelled match as Unknown; 0.0.57 fixed that.
 */
class MatchStatusScenarioIT {

    private static final String CLOSED = "feed/odds_change/odds_change_closed_with_winner.xml";

    @Test
    void aMatchCancelledOnTheFeedReadsAsCancelled() throws InterruptedException {
        assertThat(statusAfterOddsChangeWith("5")).isEqualTo(EventStatus.Cancelled);
    }

    @Test
    void aFeedStatusTheSdkDoesNotKnowReadsAsUnknown() throws InterruptedException {
        assertThat(statusAfterOddsChangeWith("99")).isEqualTo(EventStatus.Unknown);
    }

    /** Publishes an odds change whose sport_event_status carries this status number. */
    private static EventStatus statusAfterOddsChangeWith(String status) throws InterruptedException {
        String message = Fixtures.read(CLOSED).replace("status=\"4\"", "status=\"" + status + "\"");
        assertThat(message).as("status 4 replaced").contains("status=\"" + status + "\"");
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            feed.publish(message);

            Match match = (Match) received.next(OddsChange.class).getEvent();
            // The feed's status lands in the cache on the SDK's own thread, next to the callback. Until
            // it does, a read falls back to the match summary, which says closed (Finished).
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            EventStatus current = statusOf(match);
            while ((current == null || current == EventStatus.Finished) && System.nanoTime() < deadline) {
                Thread.sleep(100);
                current = statusOf(match);
            }
            rest.awaitQuiet();
            return current;
        }
    }

    private static EventStatus statusOf(Match match) {
        MatchStatus status = match.getStatus();
        return status == null ? null : status.getStatus();
    }
}
