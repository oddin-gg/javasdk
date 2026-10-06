package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.ProfileFields.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The competitor, player, tournament and sport caches over the real REST client, against the fake API. */
class ProfileCachesTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final URN COMPETITOR = URN.parse("od:competitor:47214");
    private static final URN PLAYER = URN.parse("od:player:9001");
    private static final URN TOURNAMENT = URN.parse("od:tournament:1042");
    private static final URN LOL = URN.parse("od:sport:1");
    private static final URN CS2 = URN.parse("od:sport:2");
    private static final String COMPETITOR_PROFILE_EN = "/v1/sports/en/competitors/od:competitor:47214/profile";
    private static final String PLAYER_PROFILE_EN = "/v1/sports/en/players/od:player:9001/profile";
    private static final String TOURNAMENT_INFO_EN = "/v1/sports/en/tournaments/od:tournament:1042/info";
    private static final String SPORTS_EN = "/v1/sports/en/sports";
    private static final String LOL_TOURNAMENTS_EN = "/v1/sports/en/sports/od:sport:1/tournaments";

    private final FakeTime time = new FakeTime();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private FakeRestServer api;
    private ApiClient client;
    private ProfileCaches caches;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(Duration.ofSeconds(10))
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        caches = new ProfileCaches(client, Duration.ofSeconds(10), threads, time, time);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void aCompetitorProfileWritesTheCompetitorAndFillsItsPlayersAndSport() {
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        Entry competitor = caches.competitor(COMPETITOR, EN, null);
        assertThat(competitor.get(COMPETITOR_NAME, EN)).isEqualTo("Team Alpha");
        assertThat(competitor.get(COMPETITOR_ABBREVIATION, EN)).isEqualTo("TA");
        assertThat(competitor.get(COUNTRY, EN)).isEqualTo("Czechia");
        assertThat(competitor.get(COUNTRY_CODE, null)).isEqualTo("CZE");
        assertThat(competitor.get(VIRTUAL, null)).isFalse();
        assertThat(competitor.get(UNDERAGE, null)).isEqualTo(-1);
        assertThat(competitor.get(ICON_PATH, null)).isEqualTo("/icons/alpha.svg");
        assertThat(competitor.get(PLAYERS, null)).containsExactly(PLAYER);
        caches.competitor(COMPETITOR, EN, null);
        assertThat(api.requests("GET", COMPETITOR_PROFILE_EN)).as("fresh").hasSize(1);

        Entry listed = requireNonNull(caches.cachedPlayer(PLAYER));
        assertThat(listed.get(PLAYER_NAME, EN)).isEqualTo("Player One");
        assertThat(listed.get(FULL_NAME, EN)).isEqualTo("Player One Full");
        assertThat(requireNonNull(caches.cachedSport(LOL)).get(SPORT_NAME, EN)).isEqualTo("League of Legends");

        api.respond(PLAYER_PROFILE_EN, 200, Fixtures.read("rest/player/player_profile.xml"));
        Entry player = caches.player(PLAYER, EN, null);
        assertThat(api.requests("GET", PLAYER_PROFILE_EN))
                .as("the listing only filled: the profile is loaded")
                .hasSize(1);
        assertThat(player.get(PLAYER_UNDERAGE, null)).as("the profile's").isEqualTo(1);
        assertThat(player.get(FULL_NAME, EN))
                .as("optional, and left out of the profile: kept")
                .isEqualTo("Player One Full");
        assertThat(player.get(PLAYER_SPORT, null)).isEqualTo("od:sport:1");
    }

    @Test
    void aProfileNeedsNoWarmUpWhileItIsFreshOrLoading() throws Exception {
        api.respond(
                COMPETITOR_PROFILE_EN,
                FakeRestServer.Reply.of(200, Fixtures.read("rest/competitor/competitor_profile.xml"))
                        .after(Duration.ofMillis(500)));
        api.respond(
                PLAYER_PROFILE_EN,
                FakeRestServer.Reply.of(200, Fixtures.read("rest/player/player_profile.xml"))
                        .after(Duration.ofMillis(500)));
        assertThat(caches.competitorWarm(COMPETITOR, EN)).as("never loaded").isFalse();
        assertThat(caches.playerWarm(PLAYER, EN)).isFalse();

        var competitor = threads.submit(() -> caches.competitor(COMPETITOR, EN, null));
        api.awaitRequest("GET", COMPETITOR_PROFILE_EN);
        assertThat(caches.competitorWarm(COMPETITOR, EN))
                .as("its load under way")
                .isTrue();
        competitor.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(caches.competitorWarm(COMPETITOR, EN)).as("fresh").isTrue();
        assertThat(caches.competitorWarm(COMPETITOR, Locale.GERMAN))
                .as("not in German")
                .isFalse();
        assertThat(caches.playerWarm(PLAYER, EN))
                .as("only filled by the competitor's profile: its own is not loaded")
                .isFalse();

        var player = threads.submit(() -> caches.player(PLAYER, EN, null));
        api.awaitRequest("GET", PLAYER_PROFILE_EN);
        assertThat(caches.playerWarm(PLAYER, EN)).as("its load under way").isTrue();
        player.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(caches.playerWarm(PLAYER, EN)).as("fresh").isTrue();

        time.advance(ProfileCaches.PROFILE_AGE.plusMinutes(1));
        assertThat(caches.competitorWarm(COMPETITOR, EN)).as("out of date").isFalse();
        assertThat(caches.playerWarm(PLAYER, EN)).isFalse();
    }

    @Test
    void aProfileWithAnEmptyPlayerListHasNoPlayers() {
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        caches.competitor(COMPETITOR, EN, null);
        time.advance(ProfileCaches.PROFILE_AGE.plusMinutes(1));
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile_no_players.xml"));
        Entry competitor = caches.competitor(COMPETITOR, EN, null);
        assertThat(competitor.get(PLAYERS, null))
                .as("always sent: the empty list replaces")
                .isEmpty();
        assertThat(competitor.get(COMPETITOR_ABBREVIATION, EN)).isEmpty();
    }

    @Test
    void aTournamentsInfoIsItsAuthorityOverWhatItsSportListed() {
        api.respond(LOL_TOURNAMENTS_EN, 200, Fixtures.read("rest/sport_tournaments/sport_tournaments.xml"));
        Entry sport = caches.sportTournaments(LOL, EN, null);
        assertThat(sport.get(SPORT_TOURNAMENTS, null)).containsExactly(TOURNAMENT, URN.parse("od:tournament:1043"));
        caches.sportTournaments(LOL, EN, null);
        assertThat(api.requests("GET", LOL_TOURNAMENTS_EN)).hasSize(1);

        api.respond(TOURNAMENT_INFO_EN, 200, Fixtures.read("rest/tournament_info/tournament_info.xml"));
        Entry tournament = caches.tournament(TOURNAMENT, EN, null);
        assertThat(api.requests("GET", TOURNAMENT_INFO_EN))
                .as("listed only: loaded")
                .hasSize(1);
        assertThat(tournament.get(TOURNAMENT_NAME, EN))
                .as("the info's, over the list's")
                .isEqualTo("Test Tournament");
        assertThat(tournament.get(TOURNAMENT_SPORT_ID, null)).isEqualTo(LOL);
        assertThat(tournament.get(RISK_TIER, null)).isEqualTo(1);
        assertThat(tournament.get(TOURNAMENT_SCHEDULED, null)).isEqualTo(Instant.parse("2026-08-26T18:00:00Z"));
        assertThat(tournament.get(TOURNAMENT_ABBREVIATION, EN)).isEmpty();
    }

    @Test
    void aSportListsEachTournamentOnceInTheOrderTheApiSent() {
        api.respond(
                LOL_TOURNAMENTS_EN,
                200,
                Fixtures.read("rest/sport_tournaments/sport_tournaments.xml").replace("<tournaments>", """
                                <tournaments>
                                    <tournament id="od:tournament:1043" name="Second Tournament" abbreviation="" risk_tier="2">
                                        <sport id="od:sport:1" name="League of Legends" abbreviation="LoL"/>
                                    </tournament>"""));
        Entry sport = caches.sportTournaments(LOL, EN, null);
        assertThat(sport.get(SPORT_TOURNAMENTS, null))
                .as("the repeat dropped, the first place kept")
                .containsExactly(URN.parse("od:tournament:1043"), TOURNAMENT);
    }

    @Test
    void theSportListIsLoadedWholeOncePerLocaleAndAgain() {
        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        assertThat(caches.sports(EN, null)).containsExactly(LOL, CS2);
        Entry cs2 = caches.sport(CS2, EN, null);
        assertThat(cs2.get(SPORT_NAME, EN)).isEqualTo("Counter-Strike 2");
        assertThat(cs2.get(SPORT_ICON_PATH, null)).isEqualTo("/icons/cs2.svg");
        assertThat(caches.sport(LOL, EN, null).get(SPORT_ICON_PATH, null)).isEmpty();
        assertThat(caches.sport(URN.parse("od:sport:99"), EN, null).get(SPORT_NAME, EN))
                .as("not in the list")
                .isNull();
        assertThat(api.requests("GET", SPORTS_EN))
                .as("one list for all of them")
                .hasSize(1);

        time.advance(ProfileCaches.PROFILE_AGE.plusMinutes(1));
        caches.sports(EN, null);
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(2);
    }

    @Test
    void whatAMatchSaysOfItsCompetitorsAndTournamentOnlyFills() throws Exception {
        var summary = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES)
                .decode(
                        Fixtures.read("rest/match_summary/match_summary.xml").getBytes(UTF_8),
                        RAMatchSummaryEndpoint.class);
        var started = caches.startMany(() -> false);
        caches.fillCompetitors(summary.getSportEvent().getCompetitors().getCompetitor(), EN, started);
        caches.fillTournament(summary.getSportEvent().getTournament(), EN, started);
        Entry filled = requireNonNull(caches.cachedCompetitor(COMPETITOR));
        assertThat(filled.get(COMPETITOR_NAME, EN)).isEqualTo("Team Alpha");
        assertThat(filled.isAuthoritative(COMPETITOR_NAME, EN)).isFalse();
        assertThat(requireNonNull(caches.cachedSport(URN.parse("od:sport:23"))).get(SPORT_NAME, EN))
                .isEqualTo("PenaltyArena");
        Entry tournament = requireNonNull(caches.cachedTournament(URN.parse("od:tournament:1042")));
        assertThat(tournament.get(TOURNAMENT_NAME, EN)).isEqualTo("Test Tournament");
        assertThat(tournament.isAuthoritative(TOURNAMENT_NAME, EN)).isFalse();
        assertThat(api.requests()).as("a fill loads nothing").isEmpty();

        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        assertThat(caches.competitor(COMPETITOR, EN, null).get(COUNTRY, EN)).isEqualTo("Czechia");
        assertThat(api.requests("GET", COMPETITOR_PROFILE_EN)).hasSize(1);
    }

    @Test
    void clearDropsEverythingCached() {
        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        caches.sports(EN, null);
        caches.competitor(COMPETITOR, EN, null);
        caches.clear();
        assertThat(caches.sports(EN, null)).containsExactly(LOL, CS2);
        caches.competitor(COMPETITOR, EN, null);
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(2);
        assertThat(api.requests("GET", COMPETITOR_PROFILE_EN)).hasSize(2);
        assertThat(caches.cachedCompetitor(COMPETITOR)).isNotNull();
        assertThat(caches.sport(CS2, EN, null).get(SPORT_NAME, EN))
                .as("the list loaded after the clear writes its sports")
                .isEqualTo("Counter-Strike 2");
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(2);
    }

    @Test
    void aTournamentsCompetitorsAreItsOwnListsOrElseTheInfos() {
        String own = Fixtures.read("rest/tournament_info/tournament_info.xml")
                .replace(
                        "<sport id=\"od:sport:1\" name=\"League of Legends\" abbreviation=\"LoL\"/>\n    </tournament>",
                        "<sport id=\"od:sport:1\" name=\"League of Legends\" abbreviation=\"LoL\"/>\n"
                                + "        <competitors><competitor id=\"od:competitor:2\" name=\"Two\" abbreviation=\"T2\" underage=\"0\"/>"
                                + "<competitor id=\"od:competitor:1\" name=\"One\" abbreviation=\"T1\" underage=\"0\"/></competitors>\n"
                                + "    </tournament>\n"
                                + "    <competitors><competitor id=\"od:competitor:9\" name=\"Nine\" abbreviation=\"T9\" underage=\"0\"/></competitors>");
        api.respond(TOURNAMENT_INFO_EN, 200, own);
        assertThat(caches.tournament(TOURNAMENT, EN, null).get(TOURNAMENT_COMPETITORS, null))
                .as("the tournament element's, in its order")
                .containsExactly(URN.parse("od:competitor:2"), URN.parse("od:competitor:1"));
        for (String id : List.of("od:competitor:2", "od:competitor:1")) {
            Entry fromElement = requireNonNull(caches.cachedCompetitor(URN.parse(id)));
            assertThat(fromElement.get(COMPETITOR_NAME, EN)).isNotNull();
            assertThat(fromElement.isAuthoritative(COMPETITOR_NAME, EN)).isFalse();
        }
        Entry listed = requireNonNull(caches.cachedCompetitor(URN.parse("od:competitor:9")));
        assertThat(listed.get(COMPETITOR_NAME, EN)).as("both lists fill").isEqualTo("Nine");
        assertThat(listed.isAuthoritative(COMPETITOR_NAME, EN)).isFalse();

        var other = URN.parse("od:tournament:7");
        api.respond(
                "/v1/sports/en/tournaments/od:tournament:7/info",
                200,
                Fixtures.read("rest/tournament_info/tournament_info.xml")
                        .replace("od:tournament:1042", "od:tournament:7")
                        .replace(
                                "</tournament>",
                                "</tournament>\n    <competitors><competitor id=\"od:competitor:9\" name=\"Nine\""
                                        + " abbreviation=\"T9\" underage=\"0\"/></competitors>"));
        assertThat(caches.tournament(other, EN, null).get(TOURNAMENT_COMPETITORS, null))
                .as("none in the element: the info's own")
                .containsExactly(URN.parse("od:competitor:9"));
    }

    @Test
    void aSportListAClearOvertookIsNotReadFrom() throws Exception {
        api.respond(
                SPORTS_EN,
                FakeRestServer.Reply.of(200, Fixtures.read("rest/sports/sports.xml"))
                        .after(Duration.ofMillis(500)),
                FakeRestServer.Reply.of(
                        200, Fixtures.read("rest/sports/sports.xml").replace("Counter-Strike 2", "CS2 again")));
        var loading = threads.submit(() -> caches.sports(EN, null));
        api.awaitRequest("GET", SPORTS_EN);
        caches.clear();
        loading.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(caches.sport(CS2, EN, null).get(SPORT_NAME, EN))
                .as("the list from before the clear wrote nothing, so it is loaded again")
                .isEqualTo("CS2 again");
    }

    @Test
    void anAlwaysSentFieldLeftOutIsClearedAndAnOptionalOneKept() {
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        caches.competitor(COMPETITOR, EN, null);
        // the newer profile, in another locale, without its player list or country code
        String bare = Fixtures.read("rest/competitor/competitor_profile.xml")
                .replaceAll("(?s)<players>.*</players>", "")
                .replace(" country_code=\"CZE\"", "");
        api.respond("/v1/sports/de/competitors/od:competitor:47214/profile", 200, bare);
        Entry competitor = caches.competitor(COMPETITOR, Locale.GERMAN, null);
        assertThat(competitor.get(PLAYERS, null)).as("always sent: gone").isNull();
        assertThat(competitor.isAuthoritative(PLAYERS, null)).isTrue();
        assertThat(competitor.get(COUNTRY_CODE, null)).as("optional: kept").isEqualTo("CZE");

        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        api.respond(
                "/v1/sports/de/sports",
                200,
                Fixtures.read("rest/sports/sports.xml").replace(" icon_path=\"/icons/cs2.svg\"", ""));
        caches.sports(EN, null);
        assertThat(caches.sport(CS2, Locale.GERMAN, null).get(SPORT_ICON_PATH, null))
                .as("the sport list always sends it: the newer list's leaving it out clears it")
                .isNull();
    }

    @Test
    void anEmptyListInTheTournamentElementGivesWayToTheInfosOwn() {
        api.respond(
                TOURNAMENT_INFO_EN,
                200,
                Fixtures.read("rest/tournament_info/tournament_info.xml")
                        .replace(
                                "</tournament>",
                                "<competitors/></tournament>\n    <competitors><competitor id=\"od:competitor:9\""
                                        + " name=\"Nine\" abbreviation=\"T9\" underage=\"0\"/></competitors>"));
        assertThat(caches.tournament(TOURNAMENT, EN, null).get(TOURNAMENT_COMPETITORS, null))
                .containsExactly(URN.parse("od:competitor:9"));
    }

    @Test
    void aReadAfterAClearLoadsThoughALoadFromBeforeWasUnderWay() throws Exception {
        api.respond(
                COMPETITOR_PROFILE_EN,
                FakeRestServer.Reply.of(200, Fixtures.read("rest/competitor/competitor_profile.xml"))
                        .after(Duration.ofMillis(500)),
                FakeRestServer.Reply.of(
                        200,
                        Fixtures.read("rest/competitor/competitor_profile.xml")
                                .replace("Team Alpha", "Team Alpha Again")));
        var before = threads.submit(() -> caches.competitor(COMPETITOR, EN, null));
        api.awaitRequest("GET", COMPETITOR_PROFILE_EN);
        caches.clear();
        assertThat(caches.competitor(COMPETITOR, EN, null).get(COMPETITOR_NAME, EN))
                .isEqualTo("Team Alpha Again");
        assertThat(before.get(10, java.util.concurrent.TimeUnit.SECONDS).get(COMPETITOR_NAME, EN))
                .isEqualTo("Team Alpha Again");
    }

    @Test
    void eachLocaleHasASportListOfItsOwn() {
        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        api.respond(
                "/v1/sports/de/sports",
                200,
                Fixtures.read("rest/sports/sports.xml").replace("Counter-Strike 2", "Gegenschlag 2"));
        caches.sports(EN, null);
        assertThat(caches.sport(CS2, Locale.GERMAN, null).get(SPORT_NAME, Locale.GERMAN))
                .isEqualTo("Gegenschlag 2");
        assertThat(api.requests("GET", "/v1/sports/de/sports")).hasSize(1);
        caches.sports(EN, null);
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(1);
        assertThat(caches.sport(CS2, EN, null).get(SPORT_NAME, EN)).isEqualTo("Counter-Strike 2");
    }

    @Test
    void aSportWhoseEntryWentWhileItsListIsFreshIsLoadedAgain() {
        // the wall clock stands while the entries age: a step back of the wall clock, or a sport the
        // size bound dropped, leaves a list that is fresh over a sport that is gone
        var ages = new FakeTime();
        var caches = new ProfileCaches(client, Duration.ofSeconds(10), threads, time, ages);
        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        caches.sports(EN, null);
        ages.advance(ProfileCaches.PROFILE_AGE.plusMinutes(1));
        assertThat(caches.sport(CS2, EN, null).get(SPORT_NAME, EN))
                .as("the list names it, so it is loaded again")
                .isEqualTo("Counter-Strike 2");
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(2);
        assertThat(caches.sport(URN.parse("od:sport:99"), EN, null).get(SPORT_NAME, EN))
                .as("one the list does not name loads nothing")
                .isNull();
        assertThat(api.requests("GET", SPORTS_EN)).hasSize(2);
    }

    @Test
    void aClearBetweenAReadsCheckAndItsReadDoesNotLeaveItEmpty() {
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        api.respond(PLAYER_PROFILE_EN, 200, Fixtures.read("rest/player/player_profile.xml"));
        api.respond(TOURNAMENT_INFO_EN, 200, Fixtures.read("rest/tournament_info/tournament_info.xml"));
        api.respond(SPORTS_EN, 200, Fixtures.read("rest/sports/sports.xml"));
        api.respond(LOL_TOURNAMENTS_EN, 200, Fixtures.read("rest/sport_tournaments/sport_tournaments.xml"));

        caches.competitor(COMPETITOR, EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.competitor(COMPETITOR, EN, null).get(COMPETITOR_NAME, EN))
                .as("the competitor as it was when the read looked")
                .isEqualTo("Team Alpha");

        caches.player(PLAYER, EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.player(PLAYER, EN, null).get(PLAYER_NAME, EN)).isEqualTo("Player One");

        caches.tournament(TOURNAMENT, EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.tournament(TOURNAMENT, EN, null).get(TOURNAMENT_NAME, EN))
                .isEqualTo("Test Tournament");

        caches.sportTournaments(LOL, EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.sportTournaments(LOL, EN, null).get(SPORT_TOURNAMENTS, null))
                .hasSize(2);

        caches.sports(EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.sports(EN, null)).containsExactly(LOL, CS2);

        caches.sports(EN, null);
        time.onNextInstant(caches::clear);
        assertThat(caches.sport(CS2, EN, null).get(SPORT_NAME, EN)).isEqualTo("Counter-Strike 2");
    }

    @Test
    void clearingOneCompetitorOrTournamentDropsOnlyIt() {
        api.respond(COMPETITOR_PROFILE_EN, 200, Fixtures.read("rest/competitor/competitor_profile.xml"));
        api.respond(TOURNAMENT_INFO_EN, 200, Fixtures.read("rest/tournament_info/tournament_info.xml"));
        api.respond(PLAYER_PROFILE_EN, 200, Fixtures.read("rest/player/player_profile.xml"));
        caches.competitor(COMPETITOR, EN, null);
        caches.tournament(TOURNAMENT, EN, null);
        caches.player(PLAYER, EN, null);

        caches.clearCompetitor(COMPETITOR);
        caches.competitor(COMPETITOR, EN, null);
        caches.tournament(TOURNAMENT, EN, null);
        assertThat(api.requests("GET", COMPETITOR_PROFILE_EN)).hasSize(2);
        assertThat(api.requests("GET", TOURNAMENT_INFO_EN)).hasSize(1);

        caches.clearTournament(TOURNAMENT);
        caches.tournament(TOURNAMENT, EN, null);
        caches.competitor(COMPETITOR, EN, null);
        caches.player(PLAYER, EN, null);
        assertThat(api.requests("GET", TOURNAMENT_INFO_EN)).hasSize(2);
        assertThat(api.requests("GET", COMPETITOR_PROFILE_EN)).hasSize(2);
        assertThat(api.requests("GET", PLAYER_PROFILE_EN)).hasSize(1);
    }
}
