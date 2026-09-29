package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Names in more than one language. Each locale is fetched from the API in that locale, and a
 * name comes back in the locale it is asked for, whichever was loaded first.
 *
 * <p>The vendored fixtures are English only, so the German answers are the English fixtures with
 * the names translated, served on the German paths.
 */
class LocaleScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN HOME = URN.parse("od:competitor:47214");
    private static final String SUMMARY = "/v1/sports/%s/sport_events/" + MATCH + "/summary";
    private static final String HOME_PROFILE = "/v1/sports/%s/competitors/" + HOME + "/profile";

    @Test
    void theMatchOfAMessageHasItsNameInEveryLocaleAskedFor() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            servesGerman(rest);
            Received received = sdk.open(MessageInterest.ALL);
            feed.publishFixture("feed/odds_change/odds_change_markets_only.xml");

            var match = (Match) received.next(OddsChange.class).getEvent();
            assertThat(match.getName(Locale.ENGLISH)).as("name in English").isEqualTo("Team Alpha vs Team Beta");
            assertThat(match.getName(Locale.GERMAN))
                    .as("name in German")
                    .isEqualTo("Mannschaft Alpha gegen Mannschaft Beta");
            assertThat(match.getName(Locale.ENGLISH))
                    .as("name in English, after German was loaded")
                    .isEqualTo("Team Alpha vs Team Beta");
            assertThat(match.getHomeCompetitor().getName(Locale.GERMAN))
                    .as("home team in German")
                    .isEqualTo("Mannschaft Alpha");

            assertThat(rest.requests())
                    .extracting(RecordedRequest::path)
                    .as("what the SDK asked the API for")
                    .contains(SUMMARY.formatted("en"), SUMMARY.formatted("de"), HOME_PROFILE.formatted("de"));
        }
    }

    /** A manager getter given a locale reads in that locale, without the feed. */
    @Test
    void aMatchAskedForInALocaleHasItsNameInThatLocale() {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            servesGerman(rest);

            var match = sdk.oddsFeed().getSportsInfoManager().getMatch(MATCH, Locale.GERMAN);
            assertThat(match.getName(Locale.GERMAN))
                    .as("name in German")
                    .isEqualTo("Mannschaft Alpha gegen Mannschaft Beta");
            assertThat(sdk.oddsFeed()
                            .getSportsInfoManager()
                            .getCompetitor(HOME, Locale.GERMAN)
                            .getName(Locale.GERMAN))
                    .as("home team in German")
                    .isEqualTo("Mannschaft Alpha");
            assertThat(rest.requests())
                    .extracting(RecordedRequest::path)
                    .as("what the SDK asked the API for")
                    .contains(SUMMARY.formatted("de"), HOME_PROFILE.formatted("de"));
        }
    }

    /** The German match summary and home team profile. */
    private static void servesGerman(FakeRestServer rest) {
        var summary = Fixtures.read("rest/match_summary/match_summary.xml");
        summary = Fixtures.replace(
                summary, "name=\"Team Alpha vs Team Beta\"", "name=\"Mannschaft Alpha gegen Mannschaft Beta\"");
        summary = Fixtures.replace(summary, "name=\"Team Alpha\"", "name=\"Mannschaft Alpha\"");
        summary = Fixtures.replace(summary, "name=\"Team Beta\"", "name=\"Mannschaft Beta\"");
        rest.respond(SUMMARY.formatted("de"), 200, summary);
        rest.respond(
                HOME_PROFILE.formatted("de"),
                200,
                Fixtures.replace(
                        Fixtures.read("rest/competitor/competitor_profile_no_players.xml"),
                        "Team Alpha",
                        "Mannschaft Alpha"));
    }
}
