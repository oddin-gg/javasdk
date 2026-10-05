package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What a getter does when the API cannot give it what it needs, under each exception strategy:
 * {@code THROW}, the default, propagates the failure to the caller, as an {@link
 * ItemNotFoundException} for an entity and as the API's own {@link ApiException} for a list the API
 * is asked for directly; {@code CATCH} returns null, but for a match's competitors, which are none.
 */
class ExceptionStrategyScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN HOME = URN.parse("od:competitor:47214");
    private static final URN AWAY = URN.parse("od:competitor:47215");
    private static final String SUMMARY = "/v1/sports/en/sport_events/" + MATCH + "/summary";
    private static final String AWAY_PROFILE = "/v1/sports/en/competitors/" + AWAY + "/profile";
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
                    .isExactlyInstanceOf(ItemNotFoundException.class);
            assertThatThrownBy(() -> sportsInfo.getMatch(MATCH).getCompetitors())
                    .as("the competitors of the match")
                    .isExactlyInstanceOf(ItemNotFoundException.class);
            assertThatThrownBy(sportsInfo::getLiveMatches)
                    .as("the live matches")
                    .isExactlyInstanceOf(ApiException.class);
        }
    }

    @Test
    void underCatchAGetterTheApiCannotServeReturnsNull() {
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
            assertThat(sportsInfo.getMatch(MATCH).getCompetitors())
                    .as("the competitors of the match: none, not null")
                    .isNotNull()
                    .isEmpty();
        }
    }

    /**
     * A match lists its competitors from its summary: one whose profile the API cannot serve is still
     * listed, and only its own getters fail.
     */
    @Test
    void aCompetitorWhoseProfileCannotLoadIsStillListed() {
        for (var strategy : ExceptionHandlingStrategy.values()) {
            try (FakeRestServer rest = FakeRestServer.start();
                    Sdk sdk = Sdk.withoutFeed(rest, builder -> builder.setExceptionHandlingStrategy(strategy))) {
                rest.respond(AWAY_PROFILE, 500, "");
                List<Competitor> competitors =
                        sdk.oddsFeed().getSportsInfoManager().getMatch(MATCH).getCompetitors();

                assertThat(competitors)
                        .as(strategy + ": the competitors of the match")
                        .extracting(Competitor::getId)
                        .containsExactly(HOME, AWAY);
                assertThat(competitors.get(0).getName(Locale.ENGLISH))
                        .as(strategy + ": the home name")
                        .isEqualTo("Team Alpha");
                Competitor away = competitors.get(1);
                if (strategy == ExceptionHandlingStrategy.THROW) {
                    assertThatThrownBy(() -> away.getName(Locale.ENGLISH))
                            .as("the away name")
                            .isExactlyInstanceOf(ItemNotFoundException.class);
                } else {
                    assertThat(away.getName(Locale.ENGLISH)).as("the away name").isNull();
                }
                rest.awaitQuiet();
            }
        }
    }
}
