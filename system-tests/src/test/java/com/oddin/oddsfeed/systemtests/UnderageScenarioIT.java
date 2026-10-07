package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.UnderageStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Underage as 0.0.58 has it: a player's from the player's profile, a competitor's number also as a
 * status. The API sends -1 for unknown, 0 for no and 1 for yes.
 */
class UnderageScenarioIT {

    private static final URN PLAYER = URN.parse("od:player:9001");
    private static final URN HOME = URN.parse("od:competitor:47214");
    private static final String PLAYER_PROFILE = "/v1/sports/%s/players/" + PLAYER + "/profile";
    private static final String HOME_PROFILE = "/v1/sports/%s/competitors/" + HOME + "/profile";

    /**
     * The player profile fixture says 1. A profile that leaves the attribute out keeps the value an
     * earlier one sent, and one that sends -1 makes it unknown again, for every locale.
     */
    @Test
    void aPlayersUnderageIsItsProfilesAndAProfileWithoutItKeepsIt() {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            String profile = Fixtures.read("rest/player/player_profile.xml");
            String withoutIt = profile.replace(" underage=\"1\"", "");
            assertThat(withoutIt).as("the German profile").doesNotContain("underage");
            rest.respond(PLAYER_PROFILE.formatted("de"), 200, withoutIt);
            rest.respond(PLAYER_PROFILE.formatted("fr"), 200, profile.replace("underage=\"1\"", "underage=\"-1\""));
            var sportsInfo = sdk.oddsFeed().getSportsInfoManager();

            assertThat(sportsInfo.getPlayer(PLAYER, Locale.ENGLISH).getUnderage())
                    .as("the English profile's")
                    .isEqualTo(UnderageStatus.YES);
            assertThat(sportsInfo.getPlayer(PLAYER, Locale.GERMAN).getUnderage())
                    .as("the German profile leaves it out")
                    .isEqualTo(UnderageStatus.YES);
            assertThat(sportsInfo.getPlayer(PLAYER, Locale.FRENCH).getUnderage())
                    .as("the French profile sends -1")
                    .isEqualTo(UnderageStatus.UNKNOWN);
            assertThat(sportsInfo.getPlayer(PLAYER, Locale.ENGLISH).getUnderage())
                    .as("in English again")
                    .isEqualTo(UnderageStatus.UNKNOWN);
            rest.awaitQuiet();
        }
    }

    @Test
    @SuppressWarnings("deprecation") // the raw number, next to the status it maps to
    void aCompetitorsUnderageNumberIsAlsoAStatus() {
        for (var wire : new String[] {"-1", "0", "1"}) {
            try (FakeRestServer rest = FakeRestServer.start();
                    Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
                rest.respond(
                        HOME_PROFILE.formatted("en"),
                        200,
                        Fixtures.read("rest/competitor/competitor_profile.xml")
                                .replace("underage=\"-1\"\n", "underage=\"" + wire + "\"\n"));
                Competitor competitor = sdk.oddsFeed().getSportsInfoManager().getCompetitor(HOME);
                assertThat(competitor.getUnderage()).as(wire).isEqualTo(Integer.valueOf(wire));
                assertThat(competitor.getUnderageStatus())
                        .as(wire)
                        .isEqualTo(
                                switch (wire) {
                                    case "0" -> UnderageStatus.NO;
                                    case "1" -> UnderageStatus.YES;
                                    default -> UnderageStatus.UNKNOWN;
                                });
                rest.awaitQuiet();
            }
        }
    }
}
