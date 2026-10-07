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
import com.oddin.oddsfeedsdk.api.entities.sportevent.UnderageStatus;
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
    private static final String PLAYER_DE = "/v1/sports/de/players/od:player:9001/profile";
    private static final String TOURNAMENT_EN = "/v1/sports/en/tournaments/od:tournament:1042/info";
    private static final String SPORTS_EN = "/v1/sports/en/sports";

    @Test
    @SuppressWarnings("deprecation") // the reference id, to show it loads nothing, and the raw underage
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
    void aCompetitorsPlayersLoadInTheBackground() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    PLAYER_EN,
                    Reply.of(200, Fixtures.read("rest/player/player_profile.xml"))
                            .after(Duration.ofSeconds(2)));
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
            competitor.getName(EN);
            long started = System.nanoTime();
            List<@Nullable Player> players = requireNonNull(competitor.getPlayers());
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("returned while the player's profile is still loading")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(players).extracting(Player::getId).containsExactly(PLAYER);
            world.api.awaitRequest("GET", PLAYER_EN);
            world.api.awaitQuiet();
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
    void aPlayerWhoseProfileCannotLoadIsStillListedAndFailsOnItsOwn() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 500, "");
            List<@Nullable Player> players = requireNonNull(
                    world.entities.competitor(COMPETITOR, List.of(EN)).getPlayers());
            assertThat(players)
                    .as("the profile's ids, as 0.0.x listed them")
                    .extracting(player -> requireNonNull(player).getId())
                    .containsExactly(PLAYER);
            Player player = requireNonNull(players.getFirst());
            assertThatThrownBy(() -> player.getName(EN))
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasCauseInstanceOf(ApiException.class);
            players.add(player);
            assertThat(players).as("the caller's own list").hasSize(2);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PLAYER_EN, 500, "");
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
            assertThat(competitor.getPlayers())
                    .extracting(player -> requireNonNull(player).getId())
                    .containsExactly(PLAYER);
            assertThat(requireNonNull(requireNonNull(competitor.getPlayers()).getFirst())
                            .getName(EN))
                    .isNull();
            assertThat(competitor.getName(EN)).isEqualTo("Team Alpha");
        }
    }

    @Test
    void aLocaleThatCannotLoadFailsTheNamesAsAWhole() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_DE, 500, "");
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN, DE));
            assertThatThrownBy(competitor::getNames)
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasCauseInstanceOf(ApiException.class);
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
    void aPlayersUnderageIsItsProfilesAndAProfileWithoutItKeepsIt() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            String profile = Fixtures.read("rest/player/player_profile.xml");
            world.api.respond(PLAYER_EN, 200, profile);
            world.api.respond(
                    "/v1/sports/de/players/od:player:9001/profile", 200, profile.replace(" underage=\"1\"", ""));
            world.api.respond(
                    "/v1/sports/fr/players/od:player:9001/profile",
                    200,
                    profile.replace("underage=\"1\"", "underage=\"-1\""));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage()).isEqualTo(UnderageStatus.YES);
            assertThat(world.entities.player(PLAYER, List.of(DE)).getUnderage())
                    .as("left out: the value an earlier profile sent, as 0.0.x kept it")
                    .isEqualTo(UnderageStatus.YES);
            assertThat(world.entities.player(PLAYER, List.of(Locale.FRENCH)).getUnderage())
                    .as("-1: unknown again")
                    .isEqualTo(UnderageStatus.UNKNOWN);
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .as("the English profile is fresh: not loaded again")
                    .isEqualTo(UnderageStatus.UNKNOWN);
            assertThat(world.api.requests("GET", PLAYER_EN)).hasSize(1);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    PLAYER_EN,
                    200,
                    Fixtures.read("rest/player/player_profile.xml").replace("underage=\"1\"", "underage=\"0\""));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage()).isEqualTo(UnderageStatus.NO);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    PLAYER_EN,
                    200,
                    Fixtures.read("rest/player/player_profile.xml").replace(" underage=\"1\"", ""));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .as("never sent")
                    .isEqualTo(UnderageStatus.UNKNOWN);
        }
    }

    @Test
    void aPlayerOfSeveralLocalesHasOneUnderageTheNewestProfileSaid() {
        String profile = Fixtures.read("rest/player/player_profile.xml");
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 200, profile);
            world.api.respond(PLAYER_DE, 200, profile.replace("underage=\"1\"", "underage=\"-1\""));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage()).isEqualTo(UnderageStatus.YES);
            assertThat(world.entities.player(PLAYER, List.of(EN, DE)).getUnderage())
                    .as("the German profile, loaded after the English one, says -1")
                    .isEqualTo(UnderageStatus.UNKNOWN);
            assertThat(world.api.requests("GET", PLAYER_DE)).hasSize(1);
            assertThat(world.api.requests("GET", PLAYER_EN)).as("fresh").hasSize(1);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 200, profile);
            world.api.respond(PLAYER_DE, 200, profile.replace(" underage=\"1\"", ""));
            assertThat(world.entities.player(PLAYER, List.of(DE, EN)).getUnderage())
                    .as("loaded side by side, one of them leaving it out")
                    .isEqualTo(UnderageStatus.YES);
        }
    }

    /**
     * The English profile's fetch starts first and is answered last; the German one, started later,
     * leaves the attribute out. Applied in the order they started, the English value stays.
     */
    @Test
    void aProfileAnsweredLateKeepsItsUnderageWhenALaterFetchLeftItOut() throws Exception {
        String profile = Fixtures.read("rest/player/player_profile.xml");
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, Reply.of(200, profile).after(Duration.ofSeconds(3)));
            world.api.respond(PLAYER_DE, 200, profile.replace(" underage=\"1\"", ""));
            var english = world.threads.submit(
                    () -> world.entities.player(PLAYER, List.of(EN)).getName(EN));
            world.api.awaitRequest("GET", PLAYER_EN);
            assertThat(world.entities.player(PLAYER, List.of(DE)).getName(DE)).isEqualTo("Player One");
            assertThat(english.isDone()).as("the English answer still to come").isFalse();
            english.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(world.entities.player(PLAYER, List.of(EN, DE)).getUnderage())
                    .isEqualTo(UnderageStatus.YES);
            assertThat(world.api.requests())
                    .as("both fresh: nothing loaded again")
                    .hasSize(2);
        }
    }

    @Test
    void aCompetitorsPlayerListNeitherSetsNorKeepsAPlayersUnderage() {
        String listingYes = PROFILE.replace(
                "full_name=\"Player One Full\" underage=\"-1\"", "full_name=\"Player One Full\" underage=\"1\"");
        assertThat(listingYes).isNotEqualTo(PROFILE);
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_EN, 200, listingYes);
            world.api.respond(
                    PLAYER_EN,
                    200,
                    Fixtures.read("rest/player/player_profile.xml").replace(" underage=\"1\"", ""));
            assertThat(world.entities.competitor(COMPETITOR, List.of(EN)).getName(EN))
                    .isEqualTo("Team Alpha");
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .as("only the player's own profile says it, and it left it out")
                    .isEqualTo(UnderageStatus.UNKNOWN);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PROFILE_EN, 200, PROFILE);
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage()).isEqualTo(UnderageStatus.YES);
            assertThat(world.entities.competitor(COMPETITOR, List.of(EN)).getName(EN))
                    .isEqualTo("Team Alpha");
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .as("the list's -1 does not make it unknown")
                    .isEqualTo(UnderageStatus.YES);
        }
    }

    @Test
    void aPlayersUnderageIsNotFoundOrNullAsItsOtherGetters() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 404, Fixtures.read("rest/error/not_found.xml"));
            assertThatThrownBy(() -> world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .isInstanceOf(ItemNotFoundException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PLAYER_EN, 404, Fixtures.read("rest/error/not_found.xml"));
            assertThat(world.entities.player(PLAYER, List.of(EN)).getUnderage())
                    .as("no profile: null, not unknown, as 0.0.x answered")
                    .isNull();
        }
    }

    @Test
    @SuppressWarnings("deprecation") // the raw number is read next to the status it maps to
    void aCompetitorsUnderageStatusIsItsNumberMapped() {
        for (var wire : List.of("-1", "0", "1", "7")) {
            try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
                world.api.respond(PROFILE_EN, 200, PROFILE.replace("underage=\"-1\"", "underage=\"" + wire + "\""));
                Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
                assertThat(competitor.getUnderage()).isEqualTo(Integer.valueOf(wire));
                assertThat(competitor.getUnderageStatus())
                        .as(wire)
                        .isEqualTo(
                                switch (wire) {
                                    case "0" -> UnderageStatus.NO;
                                    case "1" -> UnderageStatus.YES;
                                    default -> UnderageStatus.UNKNOWN;
                                });
            }
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(PROFILE_EN, 500, "");
            assertThat(world.entities.competitor(COMPETITOR, List.of(EN)).getUnderageStatus())
                    .as("no profile: the missing number reads as unknown, as on 0.0.x")
                    .isEqualTo(UnderageStatus.UNKNOWN);
        }
    }

    @Test
    void aPlayerTheApiDoesNotKnowIsNotFoundOrNull() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(PLAYER_EN, 404, Fixtures.read("rest/error/not_found.xml"));
            assertThatThrownBy(() -> world.entities.player(PLAYER, List.of(EN)).getName(EN))
                    .as("as 0.0.x threw it")
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasCauseInstanceOf(ApiException.class);
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
    void aTournamentBuiltWithItsSportReadsItWithoutLoadingAndLoadsItsCompetitorsInTheBackground() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
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
            world.api.awaitRequest("GET", "/v1/sports/en/competitors/od:competitor:2/profile");
            world.api.awaitRequest("GET", "/v1/sports/en/competitors/od:competitor:1/profile");
            assertThat(world.api.mostInFlight()).as("side by side").isEqualTo(2);
            assertThat(competitors.getFirst().getName(EN)).isEqualTo("Two");
        }
    }

    @Test
    void aTournamentCompetitorWhoseProfileCannotLoadIsStillListedAndFailsOnItsOwn() {
        for (var strategy : ExceptionHandlingStrategy.values()) {
            try (var world = EntityWorld.start(strategy)) {
                world.api.respond(TOURNAMENT_EN, 200, tournamentListing(2));
                world.api.respond(competitorProfile(1), 200, PROFILE.replace("Team Alpha", "One"));
                world.api.respond(competitorProfile(2), 500, "");
                List<Competitor> competitors = requireNonNull(
                        world.entities.tournament(TOURNAMENT, CS2, List.of(EN)).getCompetitors());
                assertThat(competitors)
                        .as(strategy + ": the info's ids, as 0.0.x listed them")
                        .extracting(Competitor::getId)
                        .containsExactly(URN.parse("od:competitor:1"), URN.parse("od:competitor:2"));
                competitors.add(competitors.getFirst());
                assertThat(competitors).as("the caller's own list").hasSize(3);
                assertThat(competitors.getFirst().getName(EN)).isEqualTo("One");
                Competitor failing = competitors.get(1);
                if (strategy == ExceptionHandlingStrategy.THROW) {
                    assertThatThrownBy(() -> failing.getName(EN))
                            .isInstanceOf(ItemNotFoundException.class)
                            .hasCauseInstanceOf(ApiException.class);
                } else {
                    assertThat(failing.getName(EN)).isNull();
                }
            }
        }
    }

    @Test
    void aTournamentListsManyCompetitorsWithoutWaitingForProfilesThatHang() {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            int many = 40;
            world.api.respond(TOURNAMENT_EN, 200, tournamentListing(many));
            for (int i = 1; i <= many; i++) {
                world.api.respond(competitorProfile(i), Reply.of(200, PROFILE).after(Duration.ofSeconds(3)));
            }
            Tournament tournament = world.entities.tournament(TOURNAMENT, CS2, List.of(EN));
            tournament.getName(EN);
            long started = System.nanoTime();
            assertThat(tournament.getCompetitors()).hasSize(many);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("returned without waiting for a profile")
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void aMemberIsQueuedForItsWarmUpOnceAtATime() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            var release = new java.util.concurrent.CountDownLatch(1);
            Entities.WarmLoad<String> load = (member, locale, deadline) -> {
                loads.incrementAndGet();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            world.entities.warmEach(List.of("one"), List.of(EN), (member, locale) -> false, load);
            world.entities.warmEach(List.of("one"), List.of(EN), (member, locale) -> false, load);
            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (loads.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(200);
            assertThat(loads.get())
                    .as("queued once while the first was queued or running")
                    .isEqualTo(1);
            world.entities.warmEach(List.of("one"), List.of(EN), (member, locale) -> false, load);
            deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (loads.get() == 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(loads.get()).as("queued again once it ended").isEqualTo(2);
        }
    }

    @Test
    void aMemberWhoseWarmUpFailedIsQueuedAgainByALaterList() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            Entities.WarmLoad<String> load = (member, locale, deadline) -> {
                loads.incrementAndGet();
                throw new IllegalStateException("the API said no");
            };
            world.entities.warmEach(List.of("one"), List.of(EN), (member, locale) -> false, load);
            waitFor(() -> world.sideLoads.failed() == 1);
            world.entities.warmEach(List.of("one"), List.of(EN), (member, locale) -> false, load);
            waitFor(() -> world.sideLoads.failed() == 2);
            assertThat(loads.get()).as("not left marked as under way").isEqualTo(2);
        }
    }

    @Test
    void thePerLocaleMapsAreANewOneTheCallerCanChange() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            Competitor competitor = world.entities.competitor(COMPETITOR, List.of(EN));
            requireNonNull(competitor.getNames()).put(DE, "added by the client");
            requireNonNull(competitor.getCountries()).clear();
            assertThat(competitor.getNames()).as("its own copy").containsExactly(entry(EN, "Team Alpha"));
            assertThat(competitor.getCountries()).containsExactly(entry(EN, "Czechia"));
        }
    }

    @Test
    void aMemberAFullQueueDroppedIsQueuedByALaterList() throws Exception {
        // one warm-up runs at a time with two workers, and the idle queue holds one more
        try (var world = EntityWorld.withSideLoads(1, 2)) {
            var release = new java.util.concurrent.CountDownLatch(1);
            var running = new java.util.concurrent.CountDownLatch(1);
            var ran = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            Entities.WarmLoad<String> load = (member, locale, deadline) -> {
                ran.add(member);
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            world.entities.warmEach(List.of("running"), List.of(EN), (member, locale) -> false, load);
            assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            world.entities.warmEach(List.of("queued", "dropped"), List.of(EN), (member, locale) -> false, load);
            assertThat(world.sideLoads.dropped()).as("no room for the third").isEqualTo(1);

            release.countDown();
            waitFor(() -> ran.contains("queued"));
            Thread.sleep(100);
            world.entities.warmEach(List.of("dropped"), List.of(EN), (member, locale) -> false, load);
            waitFor(() -> ran.contains("dropped"));
            assertThat(ran).as("not left marked as queued").contains("dropped");
        }
    }

    @Test
    void aBurstOfMemberWarmUpsLeavesRoomForAMessagesPreload() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            int many = 40;
            world.api.respond(TOURNAMENT_EN, 200, tournamentListing(many));
            for (int i = 1; i <= many; i++) {
                world.api.respond(competitorProfile(i), Reply.of(200, PROFILE).after(Duration.ofSeconds(3)));
            }
            Tournament tournament = world.entities.tournament(TOURNAMENT, CS2, List.of(EN));
            assertThat(tournament.getCompetitors()).hasSize(many);
            world.api.awaitRequest("GET", competitorProfile(1));

            String summary = "/v1/sports/en/sport_events/od:match:198314/summary";
            long started = System.nanoTime();
            world.matches.preload(URN.parse("od:match:198314"), List.of(EN));
            world.api.awaitRequest("GET", summary);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("the preload ran on a worker the warm-ups left free, not behind them")
                    .isLessThan(Duration.ofSeconds(2));
            assertThat(world.sideLoads.dropped()).as("nor was it dropped").isZero();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** The tournament's info, listing competitors 1 to {@code count}. */
    private static String tournamentListing(int count) {
        var listed = new StringBuilder("<competitors>");
        for (int i = 1; i <= count; i++) {
            listed.append("<competitor id=\"od:competitor:")
                    .append(i)
                    .append("\" name=\"C")
                    .append(i)
                    .append("\" abbreviation=\"C")
                    .append(i)
                    .append("\" underage=\"0\"/>");
        }
        return Fixtures.read("rest/tournament_info/tournament_info.xml")
                .replace("</tournament>", listed + "</competitors></tournament>");
    }

    private static String competitorProfile(int id) {
        return "/v1/sports/en/competitors/od:competitor:" + id + "/profile";
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
