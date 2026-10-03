package com.oddin.oddsfeedsdk.internal.entity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Competitors, players, tournaments and sports as the client reads them, over the caches and the fake API. */
class ProfileViewsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final URN COMPETITOR = URN.parse("od:competitor:47214");
    private static final URN PLAYER = URN.parse("od:player:9001");
    private static final URN TOURNAMENT = URN.parse("od:tournament:1042");
    private static final URN LOL = URN.parse("od:sport:1");
    private static final URN CS2 = URN.parse("od:sport:2");
    private static final String PROFILE = Fixtures.read("rest/competitor/competitor_profile.xml");
    private static final String PROFILE_EN = "/v1/sports/en/competitors/od:competitor:47214/profile";
    private static final String PROFILE_DE = "/v1/sports/de/competitors/od:competitor:47214/profile";
    private static final String PLAYER_EN = "/v1/sports/en/players/od:player:9001/profile";
    private static final String TOURNAMENT_EN = "/v1/sports/en/tournaments/od:tournament:1042/info";
    private static final String SPORTS_EN = "/v1/sports/en/sports";

    @Test
    @SuppressWarnings("deprecation") // the reference id is read, to show it loads nothing
    void aCompetitorReadsItsProfileInEachOfItsLocales() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_DE, 200, german(PROFILE));
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN, DE));
            assertThat(competitor.getId()).isEqualTo(COMPETITOR);
            assertThat(competitor.getRefId()).isNull();
            assertThat(world.api.requests()).as("built, not loaded").isEmpty();

            assertThat(competitor.getNames()).containsExactly(entry(EN, "Team Alpha"), entry(DE, "Mannschaft Alpha"));
            assertThat(competitor.getAbbreviations()).containsExactly(entry(EN, "TA"), entry(DE, "TA"));
            assertThat(competitor.getCountries()).containsExactly(entry(EN, "Czechia"), entry(DE, "Tschechien"));
            assertThat(competitor.getName(DE)).isEqualTo("Mannschaft Alpha");
            assertThat(competitor.getCountry(EN)).isEqualTo("Czechia");
            assertThat(competitor.getAbbreviation(DE)).isEqualTo("TA");
            assertThat(competitor.getCountryCode()).isEqualTo("CZE");
            assertThat(competitor.getVirtual()).isFalse();
            assertThat(competitor.getUnderage()).isEqualTo(-1);
            assertThat(competitor.getIconPath()).isEqualTo("/icons/alpha.svg");
            assertThat(world.api.requests("GET", PROFILE_EN)).hasSize(1);
            assertThat(world.api.requests("GET", PROFILE_DE)).hasSize(1);
        }
    }

    @Test
    void aCompetitorsPlayersAreLoadedBeforeTheyAreReturned() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            List<@Nullable Player> players = requireNonNull(
                    world.entities.competitor(COMPETITOR, List.of(EN)).getPlayers());
            assertThat(players).extracting(Player::getId).containsExactly(PLAYER);
            assertThat(world.api.requests("GET", PLAYER_EN)).hasSize(1);
            Player player = requireNonNull(players.getFirst());
            assertThat(player.getName(EN)).isEqualTo("Player One");
            assertThat(world.api.requests("GET", PLAYER_EN))
                    .as("loaded already")
                    .hasSize(1);
        }
    }

    @Test
    void aCompetitorWithoutPlayersHasNoneAndIsNotLoadedAgainForThem() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile_no_players.xml"));
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
            assertThat(competitor.getPlayers()).isEmpty();
            assertThat(competitor.getPlayers()).isEmpty();
            assertThat(world.api.requests("GET", PROFILE_EN))
                    .as("0.0.x loaded it on every call")
                    .hasSize(1);
        }
    }

    @Test
    void aPlayerThatCannotLoadFailsThePlayersUnderThrowAndNullsThemUnderCatch() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 500, "");
            assertThatThrownBy(() ->
                            world.entities.competitor(COMPETITOR, List.of(EN)).getPlayers())
                    .isInstanceOf(ApiException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PLAYER_EN, 500, "");
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
            assertThat(competitor.getPlayers()).isNull();
            assertThat(competitor.getName(EN)).isEqualTo("Team Alpha");
        }
    }

    @Test
    void aLocaleThatCannotLoadFailsTheNamesAsAWhole() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_DE, 500, "");
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN, DE));
            assertThatThrownBy(competitor::getNames).isInstanceOf(ApiException.class);
            assertThat(competitor.getName(EN)).isEqualTo("Team Alpha");
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PROFILE_DE, 500, "");
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN, DE));
            assertThat(competitor.getNames()).as("null, never the English half").isNull();
            assertThat(competitor.getCountryCode()).isNull();
        }
    }

    @Test
    void aPlayerReadsItsProfile() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    "/v1/sports/de/players/od:player:9001/profile",
                    200,
                    Fixtures.read("rest/player/player_profile.xml")
                            .replace("name=\"Player One\"", "name=\"Spieler Eins\" full_name=\"Spieler Eins Voll\""));
            Player player = world.entities.player(PLAYER, List.of(EN, DE));
            assertThat(player.getId()).isEqualTo(PLAYER);
            assertThat(player.getNames()).containsExactly(entry(EN, "Player One"), entry(DE, "Spieler Eins"));
            assertThat(player.getFullNames())
                    .as("the locales that have one")
                    .containsExactly(entry(DE, "Spieler Eins Voll"));
            assertThat(player.getFullName(EN)).isNull();
            assertThat(player.getSportIDs()).containsExactly(entry(EN, "od:sport:1"), entry(DE, "od:sport:1"));
            assertThat(player.getSportID(DE)).isEqualTo("od:sport:1");
        }
    }

    @Test
    void aPlayerTheApiDoesNotKnowIsNotFoundOrNull() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 404, Fixtures.read("rest/error/not_found.xml"));
            assertThatThrownBy(() -> world.entities.player(PLAYER, List.of(EN)).getName(EN))
                    .isInstanceOf(ApiException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PLAYER_EN, 404, Fixtures.read("rest/error/not_found.xml"));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getName(EN)).isNull();
        }
    }

    @Test
    @SuppressWarnings("deprecation") // the reference id is read, to show it loads nothing
    void aTournamentReadsItsInfo() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    TOURNAMENT_EN, 200, withLength(Fixtures.read("rest/tournament_info/tournament_info.xml")));
            Tournament tournament = world.entities.tournament(TOURNAMENT, null, List.of(EN));
            assertThat(tournament.getRefId()).isNull();
            assertThat(tournament.getLiveOddsAvailability())
                    .as("never available, and nothing loaded for it")
                    .isEqualTo(LiveOddsAvailability.NOT_AVAILABLE);
            assertThat(world.api.requests()).isEmpty();

            assertThat(tournament.getName(EN)).isEqualTo("Test Tournament");
            assertThat(tournament.getAbbreviation(EN)).isEmpty();
            assertThat(tournament.getSportId()).as("the info's").isEqualTo(LOL);
            assertThat(tournament.getScheduledTime()).isEqualTo(Date.from(Instant.parse("2026-08-26T18:00:00Z")));
            assertThat(tournament.getScheduledEndTime()).isEqualTo(Date.from(Instant.parse("2026-08-27T18:00:00Z")));
            assertThat(tournament.getStartDate()).isEqualTo(Date.from(Instant.parse("2026-08-16T00:00:00Z")));
            assertThat(tournament.getEndDate()).isEqualTo(Date.from(Instant.parse("2026-09-05T00:00:00Z")));
            assertThat(tournament.getRiskTier()).isEqualTo(1);
            assertThat(tournament.getCompetitors()).as("the info lists none").isEmpty();
            assertThat(requireNonNull(tournament.getSport()).getName(EN)).isEqualTo("League of Legends");
            assertThat(world.api.requests("GET", TOURNAMENT_EN)).hasSize(1);
        }
    }

    @Test
    void aTournamentBuiltWithItsSportReadsItWithoutLoadingAndLoadsItsCompetitors() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    TOURNAMENT_EN,
                    200,
                    Fixtures.read("rest/tournament_info/tournament_info.xml")
                            .replace(
                                    "</tournament>",
                                    "<competitors><competitor id=\"od:competitor:2\" name=\"Two\" abbreviation=\"T2\""
                                            + " underage=\"0\"/><competitor id=\"od:competitor:1\" name=\"One\""
                                            + " abbreviation=\"T1\" underage=\"0\"/></competitors></tournament>"));
            world.api.respond(
                    "/v1/sports/en/competitors/od:competitor:2/profile",
                    Reply.of(200, PROFILE.replace("Team Alpha", "Two")).after(Duration.ofMillis(300)));
            world.api.respond(
                    "/v1/sports/en/competitors/od:competitor:1/profile",
                    Reply.of(200, PROFILE.replace("Team Alpha", "One")).after(Duration.ofMillis(300)));
            Tournament tournament = world.entities.tournament(TOURNAMENT, CS2, List.of(EN));
            assertThat(tournament.getSportId()).isEqualTo(CS2);
            assertThat(requireNonNull(tournament.getSport()).getId()).isEqualTo(CS2);
            assertThat(world.api.requests()).isEmpty();

            List<Competitor> competitors = requireNonNull(tournament.getCompetitors());
            assertThat(competitors)
                    .extracting(Competitor::getId)
                    .containsExactly(URN.parse("od:competitor:2"), URN.parse("od:competitor:1"));
            assertThat(world.api.mostInFlight()).as("side by side").isEqualTo(2);
            assertThat(competitors.getFirst().getName(EN)).isEqualTo("Two");
        }
    }

    @Test
    @SuppressWarnings("deprecation") // the reference id is read, to show it loads nothing
    void aSportReadsTheSportListAndItsTournamentsLoadNothingUntilRead() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    "/v1/sports/de/sports",
                    200,
                    Fixtures.read("rest/sports/sports.xml").replace("Counter-Strike 2", "Gegenschlag 2"));
            Sport sport = world.entities.sport(CS2, List.of(EN, DE));
            assertThat(sport.getRefId()).isNull();
            assertThat(world.api.requests()).isEmpty();
            assertThat(sport.getNames()).containsExactly(entry(EN, "Counter-Strike 2"), entry(DE, "Gegenschlag 2"));
            assertThat(sport.getAbbreviation(EN)).isEqualTo("CS2");
            assertThat(sport.getIconPath(DE)).isEqualTo("/icons/cs2.svg");

            List<Tournament> tournaments = requireNonNull(sport.getTournaments());
            assertThat(tournaments)
                    .extracting(Tournament::getId)
                    .containsExactly(TOURNAMENT, URN.parse("od:tournament:1043"));
            assertThat(tournaments.getFirst().getSportId()).isEqualTo(CS2);
            assertThat(world.api.requests("GET", "/v1/sports/en/sports/od:sport:2/tournaments"))
                    .hasSize(1);
            assertThat(world.api.requests("GET", "/v1/sports/de/sports/od:sport:2/tournaments"))
                    .as("the first locale's list is the list")
                    .isEmpty();
            assertThat(world.api.requests("GET", TOURNAMENT_EN))
                    .as("a tournament loads when read")
                    .isEmpty();
        }
    }

    @Test
    void aSportTheListDoesNotNameIsNotFound() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            Sport sport = world.entities.sport(URN.parse("od:sport:99"), List.of(EN));
            assertThatThrownBy(() -> sport.getName(EN)).isInstanceOf(ItemNotFoundException.class);
            assertThat(world.api.requests("GET", SPORTS_EN)).hasSize(1);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            assertThat(world.entities
                            .sport(URN.parse("od:sport:99"), List.of(EN))
                            .getName(EN))
                    .isNull();
        }
    }

    @Test
    void anEntityIsReadInOneLocaleAtLeastAndEachLocaleOnce() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThatThrownBy(() -> world.entities.competitor(COMPETITOR, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(world.entities.competitor(COMPETITOR, List.of(EN, EN)).getNames())
                    .containsExactly(entry(EN, "Team Alpha"));
            assertThat(world.api.requests("GET", PROFILE_EN)).hasSize(1);
        }
    }

    private static String german(String profile) {
        return profile.replace("Team Alpha", "Mannschaft Alpha").replace("Czechia", "Tschechien");
    }

    private static String withLength(String info) {
        return info.replace(
                "<sport id=",
                "<tournament_length start_date=\"2026-08-16\" end_date=\"2026-09-05\"/>\n        <sport id=");
    }
}
