package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.systemtests.fake.FeedMessages.stampedAt;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What the SDK has loaded stays until something says it changed: a fixture change from the feed,
 * or the client clearing it. Then the next read fetches it again.
 */
class InvalidationScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String SUMMARY = "/v1/sports/en/sport_events/" + MATCH + "/summary";
    private static final String FIXTURE_CHANGE = "feed/fixture_change/fixture_change.xml";

    /**
     * The match is renamed on the API. Reading it again gives the name already loaded; after a
     * fixture change for it, reading gives the new one.
     */
    @Test
    void aFixtureChangeMakesTheNextReadFetchTheMatchAgain() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            feed.publishFixture("feed/odds_change/odds_change_markets_only.xml");
            var match = (Match) received.next(OddsChange.class).getEvent();
            assertThat(match.getName(Locale.ENGLISH)).as("name when first read").isEqualTo("Team Alpha vs Team Beta");

            rest.respond(SUMMARY, 200, renamed());
            assertThat(match.getName(Locale.ENGLISH))
                    .as("name read again, before any fixture change")
                    .isEqualTo("Team Alpha vs Team Beta");

            feed.publishFixture(FIXTURE_CHANGE);
            var changed = (Match) received.next(FixtureChange.class).getEvent();
            assertThat(changed.getName(Locale.ENGLISH))
                    .as("name after the fixture change")
                    .isEqualTo("Team Alpha vs Team Gamma");
            assertThat(match.getName(Locale.ENGLISH))
                    .as("name through the match the odds change gave, after it")
                    .isEqualTo("Team Alpha vs Team Gamma");
        }
    }

    /**
     * The same fixture change - same producer, match and timestamp - reaches the listener once;
     * with a new timestamp it is a new change and arrives again.
     */
    @Test
    void aRepeatedFixtureChangeIsDeliveredOnce() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            long changedAt = System.currentTimeMillis();
            var change = stampedAt(Fixtures.read(FIXTURE_CHANGE), changedAt);
            feed.publishAsIs(change);
            feed.publishAsIs(change);
            feed.publishFixture("feed/bet_stop/bet_stop_all_groups.xml");
            feed.publishAsIs(stampedAt(change, changedAt + 1));

            assertThat(received.next(Message.class)).as("first").isInstanceOf(FixtureChange.class);
            assertThat(received.next(Message.class))
                    .as("second, after the repeated change")
                    .isInstanceOf(BetStop.class);
            var third = received.next(Message.class);
            assertThat(third).as("third, a change with a new timestamp").isInstanceOf(FixtureChange.class);
            assertThat(third.getTimestamp().getCreated()).as("its timestamp").isEqualTo(changedAt + 1);
        }
    }

    /** Clearing a match through the sports info manager makes the next read fetch it again. */
    @Test
    void clearingAMatchMakesTheNextReadFetchItAgain() {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            var sportsInfo = sdk.oddsFeed().getSportsInfoManager();
            assertThat(sportsInfo.getMatch(MATCH).getName(Locale.ENGLISH))
                    .as("name when first read")
                    .isEqualTo("Team Alpha vs Team Beta");

            rest.respond(SUMMARY, 200, renamed());
            assertThat(sportsInfo.getMatch(MATCH).getName(Locale.ENGLISH))
                    .as("name read again, before clearing")
                    .isEqualTo("Team Alpha vs Team Beta");

            sportsInfo.clearMatch(MATCH);
            assertThat(sportsInfo.getMatch(MATCH).getName(Locale.ENGLISH))
                    .as("name after clearing")
                    .isEqualTo("Team Alpha vs Team Gamma");
        }
    }

    /** The match summary after the away team was replaced. */
    private static String renamed() {
        return Fixtures.replace(
                Fixtures.read("rest/match_summary/match_summary.xml"),
                "name=\"Team Alpha vs Team Beta\"",
                "name=\"Team Alpha vs Team Gamma\"");
    }
}
