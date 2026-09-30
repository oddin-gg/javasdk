package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What a getter does when the API cannot give it what it needs, under each exception strategy:
 * {@code THROW}, the default, propagates the failure to the caller; {@code CATCH} returns null -
 * for a collection too, never a partial or empty one.
 */
class ExceptionStrategyScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String SUMMARY = "/v1/sports/en/sport_events/" + MATCH + "/summary";
    private static final String LIVE_SCHEDULE = "/v1/sports/en/schedules/live/schedule";

    @Test
    void underThrowAGetterTheApiCannotServeThrows() {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            rest.respond(SUMMARY, 500, "");
            rest.respond(LIVE_SCHEDULE, 500, "");
            var sportsInfo = sdk.oddsFeed().getSportsInfoManager();

            assertThatThrownBy(() -> sportsInfo.getMatch(MATCH).getName(Locale.ENGLISH))
                    .as("the match name")
                    .isInstanceOf(OddsFeedSdkException.class);
            assertThatThrownBy(() -> sportsInfo.getMatch(MATCH).getCompetitors())
                    .as("the competitors of the match")
                    .isInstanceOf(OddsFeedSdkException.class);
            assertThatThrownBy(sportsInfo::getLiveMatches)
                    .as("the live matches")
                    .isInstanceOf(OddsFeedSdkException.class);
        }
    }

    @Test
    void underCatchAGetterTheApiCannotServeReturnsNull() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(
                        rest, builder -> builder.setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH))) {
            rest.respond(SUMMARY, 500, "");
            rest.respond(LIVE_SCHEDULE, 500, "");
            var sportsInfo = sdk.oddsFeed().getSportsInfoManager();

            assertThat(sportsInfo.getMatch(MATCH).getName(Locale.ENGLISH))
                    .as("the match name")
                    .isNull();
            assertThat(sportsInfo.getLiveMatches()).as("the live matches").isNull();
            var match = sportsInfo.getMatch(MATCH);
            KnownDifference.CATCH_GIVES_AN_EMPTY_COLLECTION.expect(
                    () -> assertThat(match.getCompetitors())
                            .as("the competitors of the match")
                            .isEmpty(),
                    () -> assertThat(match.getCompetitors())
                            .as("the competitors of the match")
                            .isNull());
        }
    }
}
