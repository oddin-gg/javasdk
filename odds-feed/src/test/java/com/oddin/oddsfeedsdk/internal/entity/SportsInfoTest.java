package com.oddin.oddsfeedsdk.internal.entity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeedsdk.api.entities.sportevent.FixtureChange;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The client's sports info manager over the caches and the fake API. */
class SportsInfoTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN OTHER_MATCH = URN.parse("od:match:198315");
    private static final URN LOL = URN.parse("od:sport:1");
    private static final URN CS2 = URN.parse("od:sport:2");
    private static final URN TOURNAMENT = URN.parse("od:tournament:1042");
    private static final URN SECOND_TOURNAMENT = URN.parse("od:tournament:1043");
    private static final String LOL_TOURNAMENTS = "/v1/sports/en/sports/od:sport:1/tournaments";
    private static final String CS2_TOURNAMENTS = "/v1/sports/en/sports/od:sport:2/tournaments";
    private static final String LIVE = "/v1/sports/en/schedules/live/schedule";

    @Test
    void theSportsAreTheSportListsInTheDefaultLocaleOrTheOneAskedFor() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getSports()).extracting(Sport::getId).containsExactly(LOL, CS2);
            assertThat(requireNonNull(world.sportsInfo.getSports()).get(1).getName(EN))
                    .isEqualTo("Counter-Strike 2");
            assertThat(world.api.requests("GET", "/v1/sports/en/sports"))
                    .as("one list")
                    .hasSize(1);
            assertThat(world.sportsInfo.getSports(DE)).hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/de/sports")).hasSize(1);
        }
    }

    @Test
    void theActiveTournamentsAreEverySportsLoadedSideBySide() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getActiveTournaments())
                    .extracting(Tournament::getId, Tournament::getSportId)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(TOURNAMENT, LOL),
                            org.assertj.core.groups.Tuple.tuple(SECOND_TOURNAMENT, LOL),
                            org.assertj.core.groups.Tuple.tuple(TOURNAMENT, CS2),
                            org.assertj.core.groups.Tuple.tuple(SECOND_TOURNAMENT, CS2));
            assertThat(world.api.requests("GET", LOL_TOURNAMENTS)).hasSize(1);
            assertThat(world.api.requests("GET", CS2_TOURNAMENTS)).hasSize(1);
        }
    }

    @Test
    void aSportWhoseTournamentsCannotLoadFailsTheActiveTournamentsUnderThrowAndIsLeftOutUnderCatch() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(CS2_TOURNAMENTS, 500, "");
            assertThatThrownBy(world.sportsInfo::getActiveTournaments)
                    .as("as 0.0.x threw it")
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasCauseInstanceOf(ApiException.class);
            assertThatThrownBy(() -> world.sportsInfo.getActiveTournaments("Counter-Strike 2"))
                    .isInstanceOf(ItemNotFoundException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(CS2_TOURNAMENTS, 500, "");
            assertThat(world.sportsInfo.getActiveTournaments())
                    .as("the other sport's, as 0.0.x left the failed one out")
                    .extracting(Tournament::getSportId)
                    .containsOnly(LOL)
                    .hasSize(2);
            assertThat(world.sportsInfo.getActiveTournaments("Counter-Strike 2"))
                    .as("none, never null")
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Test
    void withTheSportListDownTheSportsAndTheActiveTournamentsAreNoneUnderEitherStrategy() {
        for (var strategy : ExceptionHandlingStrategy.values()) {
            try (var world = EntityWorld.start(strategy)) {
                world.api.respond("/v1/sports/en/sports", 503, "");
                assertThat(world.sportsInfo.getSports())
                        .as(strategy + ": the sports")
                        .isNotNull()
                        .isEmpty();
                assertThat(world.sportsInfo.getActiveTournaments())
                        .as(strategy + ": the active tournaments")
                        .isNotNull()
                        .isEmpty();
                assertThat(world.sportsInfo.getActiveTournaments("Counter-Strike 2"))
                        .as(strategy + ": the active tournaments of a sport")
                        .isNotNull()
                        .isEmpty();
            }
        }
    }

    @Test
    void aListTheApiIsAskedForDirectlyFailsWithTheApisOwnException() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(LIVE, 500, "");
            world.api.respond("/v1/sports/en/fixtures/changes", 500, "");
            world.api.respond(LOL_TOURNAMENTS, 500, "");
            assertThatThrownBy(world.sportsInfo::getLiveMatches).isExactlyInstanceOf(ApiException.class);
            assertThatThrownBy(world.sportsInfo::getFixtureChanges).isExactlyInstanceOf(ApiException.class);
            assertThatThrownBy(() -> world.sportsInfo.getAvailableTournaments(LOL))
                    .isExactlyInstanceOf(ApiException.class);
        }
    }

    @Test
    void everyListIsANewOneTheCallerCanChange() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            var sports = requireNonNull(world.sportsInfo.getSports());
            sports.sort(java.util.Comparator.comparing((Sport s) -> String.valueOf(s.getId()))
                    .reversed());
            assertThat(sports).extracting(Sport::getId).containsExactly(CS2, LOL);
            requireNonNull(world.sportsInfo.getActiveTournaments()).clear();
            requireNonNull(world.sportsInfo.getActiveTournaments("Counter-Strike 2"))
                    .clear();
            requireNonNull(world.sportsInfo.getAvailableTournaments(LOL)).clear();
            var live = requireNonNull(world.sportsInfo.getLiveMatches());
            live.removeIf(match -> MATCH.equals(match.getId()));
            assertThat(live).extracting(Match::getId).containsExactly(OTHER_MATCH);
            requireNonNull(world.sportsInfo.getFixtureChanges()).clear();
            requireNonNull(requireNonNull(world.sportsInfo.getSports())
                            .getFirst()
                            .getTournaments())
                    .clear();
            assertThat(world.sportsInfo.getSports())
                    .as("its own copy each time")
                    .hasSize(2);
        }
    }

    @Test
    void theActiveTournamentsOfASportAreFoundByItsNameWhateverItsCase() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getActiveTournaments("counter-strike 2"))
                    .extracting(Tournament::getSportId)
                    .containsOnly(CS2)
                    .hasSize(2);
            assertThat(world.api.requests("GET", LOL_TOURNAMENTS))
                    .as("only the sport asked for")
                    .isEmpty();
            assertThat(world.sportsInfo.getActiveTournaments("chess", EN))
                    .as("no such sport")
                    .isEmpty();
        }
    }

    @Test
    void theSchedulesListTheirMatchesAndFillWhatTheySayOfThem() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getLiveMatches())
                    .extracting(Match::getId)
                    .containsExactly(MATCH, OTHER_MATCH);
            assertThat(world.api.requests("GET", LIVE)).hasSize(1);
            Entry filled = requireNonNull(world.profiles.cachedCompetitor(URN.parse("od:competitor:47216")));
            assertThat(filled.get(ProfileFields.COUNTRY, EN))
                    .as("a schedule fills")
                    .isEqualTo("Czechia");
            assertThat(filled.isAuthoritative(ProfileFields.COUNTRY, EN)).isFalse();
            assertThat(requireNonNull(world.matches.cachedMatch(OTHER_MATCH)).get(MatchFields.NAME, EN))
                    .isEqualTo("Team Gamma vs Team Delta");

            var day = Date.from(Instant.parse("2026-08-26T23:30:00Z"));
            assertThat(world.sportsInfo.getMatchesFor(day, DE)).hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/de/schedules/2026-08-26/schedule"))
                    .as("the day in UTC")
                    .hasSize(1);

            assertThat(world.sportsInfo.getListOfMatches(10, 100)).hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/en/schedules/pre/schedule"))
                    .extracting(RecordedRequest::query)
                    .containsExactly("start=10&limit=100");
        }
    }

    @Test
    void aPageOfMatchesIsOneToAHundredFromZeroUnderEitherStrategy() {
        for (var strategy : ExceptionHandlingStrategy.values()) {
            try (var world = EntityWorld.start(strategy)) {
                assertThatThrownBy(() -> world.sportsInfo.getListOfMatches(-1, 10))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> world.sportsInfo.getListOfMatches(0, 0))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> world.sportsInfo.getListOfMatches(0, 101, EN))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(world.api.requests()).isEmpty();
            }
        }
    }

    @Test
    void aScheduleThatCannotLoadFollowsTheStrategy() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(LIVE, 500, "");
            assertThatThrownBy(world.sportsInfo::getLiveMatches).isInstanceOf(ApiException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(LIVE, 500, "");
            assertThat(world.sportsInfo.getLiveMatches()).isNull();
        }
    }

    @Test
    void anEntityByIdLoadsNothingUntilRead() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(requireNonNull(world.sportsInfo.getMatch(MATCH)).getId()).isEqualTo(MATCH);
            assertThat(requireNonNull(world.sportsInfo.getCompetitor(URN.parse("od:competitor:1"), DE))
                            .getId())
                    .isEqualTo(URN.parse("od:competitor:1"));
            assertThat(requireNonNull(world.sportsInfo.getPlayer(URN.parse("od:player:1"), EN))
                            .getId())
                    .isEqualTo(URN.parse("od:player:1"));
            assertThat(world.api.requests()).isEmpty();
            assertThat(requireNonNull(world.sportsInfo.getMatch(MATCH, DE)).getName(DE))
                    .isEqualTo("Team Alpha vs Team Beta");
            assertThat(world.api.requests("GET", "/v1/sports/de/sport_events/od:match:198314/summary"))
                    .hasSize(1);
        }
    }

    @Test
    void theFixtureChangesAreTheApis() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getFixtureChanges())
                    .extracting(FixtureChange::getSportEventId, FixtureChange::getUpdateTime)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    MATCH, Date.from(Instant.parse("2026-08-26T11:59:00Z"))),
                            org.assertj.core.groups.Tuple.tuple(
                                    TOURNAMENT, Date.from(Instant.parse("2026-08-26T11:58:00Z"))));
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond("/v1/sports/de/fixtures/changes", 500, "");
            assertThat(world.sportsInfo.getFixtureChanges(DE)).isNull();
        }
    }

    @Test
    void theAvailableTournamentsAreTheSportsOwnList() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            assertThat(world.sportsInfo.getAvailableTournaments(LOL))
                    .extracting(Tournament::getId)
                    .containsExactly(TOURNAMENT, SECOND_TOURNAMENT);
            world.sportsInfo.getAvailableTournaments(LOL, EN);
            assertThat(world.api.requests("GET", LOL_TOURNAMENTS)).as("cached").hasSize(1);
        }
    }

    @Test
    void clearingAMatchATournamentOrACompetitorLoadsItAgain() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            var match = requireNonNull(world.sportsInfo.getMatch(MATCH));
            var competitor = requireNonNull(world.sportsInfo.getCompetitor(URN.parse("od:competitor:1")));
            var tournament = world.entities.tournament(TOURNAMENT, LOL, java.util.List.of(EN));
            match.getName(EN);
            requireNonNull(match.getFixture()).getStartTime();
            competitor.getName(EN);
            tournament.getName(EN);

            world.sportsInfo.clearMatch(MATCH);
            world.sportsInfo.clearTournament(TOURNAMENT);
            world.sportsInfo.clearCompetitor(URN.parse("od:competitor:1"));
            match.getName(EN);
            requireNonNull(match.getFixture()).getStartTime();
            competitor.getName(EN);
            tournament.getName(EN);
            assertThat(world.api.requests("GET", "/v1/sports/en/sport_events/od:match:198314/summary"))
                    .hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/en/sport_events/od:match:198314/fixture"))
                    .hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/en/competitors/od:competitor:1/profile"))
                    .hasSize(2);
            assertThat(world.api.requests("GET", "/v1/sports/en/tournaments/od:tournament:1042/info"))
                    .hasSize(2);
        }
    }
}
