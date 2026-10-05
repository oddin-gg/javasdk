package com.oddin.oddsfeedsdk.internal.entity;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportFormat;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TeamCompetitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A match, its status and its fixture as the client reads them, over the caches and the fake API. */
class MatchViewTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN HOME = URN.parse("od:competitor:47214");
    private static final URN AWAY = URN.parse("od:competitor:47215");
    private static final String SUMMARY_EN = "/v1/sports/en/sport_events/od:match:198314/summary";
    private static final String SUMMARY_DE = "/v1/sports/de/sport_events/od:match:198314/summary";
    private static final String FIXTURE_EN = "/v1/sports/en/sport_events/od:match:198314/fixture";
    private static final String HOME_EN = "/v1/sports/en/competitors/od:competitor:47214/profile";
    private static final String AWAY_EN = "/v1/sports/en/competitors/od:competitor:47215/profile";
    private static final String SUMMARY = Fixtures.read("rest/match_summary/match_summary.xml");

    @Test
    @SuppressWarnings("deprecation") // the reference id is read, to show it loads nothing
    void aMatchReadsItsSummary() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            Match match = world.entities.match(MATCH, List.of(EN));
            assertThat(world.api.requests()).as("built, not loaded").isEmpty();
            assertThat(match.getId()).isEqualTo(MATCH);
            assertThat(match.getRefId()).as("never sent").isNull();
            assertThat(world.api.requests()).as("a reference id loads nothing").isEmpty();

            assertThat(match.getName(EN)).isEqualTo("Team Alpha vs Team Beta");
            assertThat(match.getSportId()).isEqualTo(URN.parse("od:sport:23"));
            assertThat(match.getScheduledTime()).isEqualTo(Date.from(Instant.parse("2026-08-26T18:00:00Z")));
            assertThat(match.getScheduledEndTime()).isNull();
            assertThat(match.getLiveOddsAvailability())
                    .as("no liveodds: available, as 0.0.x read it")
                    .isEqualTo(LiveOddsAvailability.AVAILABLE);
            assertThat(match.getExtraInfo()).isNull();
            assertThat(match.getSportFormat()).as("none named: classic").isEqualTo(SportFormat.CLASSIC);
            assertThat(requireNonNull(match.getTournament()).getId()).isEqualTo(URN.parse("od:tournament:1042"));
            assertThat(world.api.requests("GET", SUMMARY_EN))
                    .as("one summary for all of it")
                    .hasSize(1);
        }
    }

    @Test
    void aMatchNamedWithItsSportReadsItWithoutLoading() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            Match match = world.entities.match(MATCH, URN.parse("od:sport:1"), List.of(EN));
            assertThat(match.getSportId()).isEqualTo(URN.parse("od:sport:1"));
            assertThat(world.api.requests()).isEmpty();
            assertThat(requireNonNull(match.getTournament()).getSportId())
                    .as("the tournament has the match's sport")
                    .isEqualTo(URN.parse("od:sport:1"));
        }
    }

    @Test
    void liveOddsTheSummarySaysAreNotAvailableAreNot() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(
                    SUMMARY_EN,
                    200,
                    SUMMARY.replace("status=\"closed\">", "status=\"closed\" liveodds=\"not_available\">"));
            assertThat(world.entities.match(MATCH, List.of(EN)).getLiveOddsAvailability())
                    .isEqualTo(LiveOddsAvailability.NOT_AVAILABLE);
        }
    }

    @Test
    void homeAndAwayComeFromTheQualifiersNotThePositions() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, swapped(SUMMARY));
            Match match = world.entities.match(MATCH, List.of(EN));
            TeamCompetitor home = requireNonNull(match.getHomeCompetitor());
            TeamCompetitor away = requireNonNull(match.getAwayCompetitor());
            assertThat(home.getId()).isEqualTo(HOME);
            assertThat(home.getQualifier()).isEqualTo("home");
            assertThat(away.getId()).isEqualTo(AWAY);
            assertThat(away.getQualifier()).isEqualTo("away");
            assertThat(world.api.requests("GET", HOME_EN))
                    .as("a side loads nothing until read")
                    .isEmpty();
        }
    }

    @Test
    void anEsportsMatchHasAnUnknownFormatAndStillItsHomeAndAway() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, withExtraInfo(SUMMARY, "esports"));
            Match match = world.entities.match(MATCH, List.of(EN));
            assertThat(match.getSportFormat()).isEqualTo(SportFormat.UNKNOWN);
            assertThat(match.getExtraInfo()).containsEntry("sport_format", "esports");
            assertThat(requireNonNull(match.getHomeCompetitor()).getId()).isEqualTo(HOME);
            assertThat(requireNonNull(match.getAwayCompetitor()).getId()).isEqualTo(AWAY);
        }
    }

    @Test
    void aRaceOrAMatchWithoutBothQualifiersHasNoHomeOrAway() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, withExtraInfo(SUMMARY, "race"));
            Match race = world.entities.match(MATCH, List.of(EN));
            assertThat(race.getSportFormat()).isEqualTo(SportFormat.RACE);
            assertThat(race.getHomeCompetitor())
                    .as("not a failure: a race has none")
                    .isNull();
            assertThat(race.getAwayCompetitor()).isNull();

            world.matches.clear(MATCH);
            world.api.respond(SUMMARY_EN, 200, SUMMARY.replace(" qualifier=\"away\"", ""));
            Match unqualified = world.entities.match(MATCH, List.of(EN));
            assertThat(unqualified.getHomeCompetitor()).as("no away: no guess").isNull();
            assertThat(unqualified.getAwayCompetitor()).isNull();
        }
    }

    @Test
    void theCompetitorsAreLoadedSideBySideBeforeTheyAreReturned() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, swapped(SUMMARY));
            var profile = Fixtures.read("rest/competitor/competitor_profile.xml");
            world.api.respond(HOME_EN, Reply.of(200, profile).after(Duration.ofMillis(300)));
            world.api.respond(
                    AWAY_EN,
                    Reply.of(200, profile.replace("Team Alpha", "Team Beta")).after(Duration.ofMillis(300)));

            List<Competitor> competitors =
                    requireNonNull(world.entities.match(MATCH, List.of(EN)).getCompetitors());
            assertThat(competitors)
                    .extracting(Competitor::getId)
                    .as("the summary's order")
                    .containsExactly(AWAY, HOME);
            assertThat(competitors)
                    .extracting(c -> ((TeamCompetitor) c).getQualifier())
                    .containsExactly("away", "home");
            assertThat(world.api.mostInFlight()).as("both at once").isEqualTo(2);
            assertThat(competitors.getFirst().getName(EN)).isEqualTo("Team Beta");
            assertThat(world.api.requests("GET", AWAY_EN)).as("loaded already").hasSize(1);
        }
    }

    @Test
    void aCompetitorThatCannotLoadFailsTheListUnderThrowAndNullsItUnderCatch() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(AWAY_EN, 500, "");
            assertThatThrownBy(() -> world.entities.match(MATCH, List.of(EN)).getCompetitors())
                    .isInstanceOf(ApiException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(AWAY_EN, 500, "");
            Match match = world.entities.match(MATCH, List.of(EN));
            assertThat(match.getCompetitors()).as("null, never a short list").isNull();
            assertThat(match.getName(EN)).as("what can load still does").isEqualTo("Team Alpha vs Team Beta");
        }
    }

    @Test
    void aMatchOfTwoLocalesLoadsThemSideBySideAndFailsAsAWhole() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, Reply.of(200, SUMMARY).after(Duration.ofMillis(300)));
            world.api.respond(
                    SUMMARY_DE,
                    Reply.of(200, SUMMARY.replace("Team Alpha vs Team Beta", "Alpha gegen Beta"))
                            .after(Duration.ofMillis(300)));
            Match match = world.entities.match(MATCH, List.of(EN, DE));
            assertThat(match.getScheduledTime()).isNotNull();
            assertThat(world.api.mostInFlight()).as("both locales at once").isEqualTo(2);
            assertThat(match.getName(DE)).isEqualTo("Alpha gegen Beta");
            assertThat(world.api.requests("GET", SUMMARY_DE)).hasSize(1);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_DE, 500, "");
            Match match = world.entities.match(MATCH, List.of(EN, DE));
            assertThatThrownBy(match::getScheduledTime).isInstanceOf(ApiException.class);
            assertThat(match.getName(EN))
                    .as("a getter of one locale loads only it")
                    .isNotNull();
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(SUMMARY_DE, 500, "");
            assertThat(world.entities.match(MATCH, List.of(EN, DE)).getCompetitors())
                    .isNull();
        }
    }

    @Test
    void aMatchTheApiDoesNotDescribeIsNotFound() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, "<match_summary generated_at=\"2026-08-26T12:00:00\"/>");
            Match match = world.entities.match(MATCH, List.of(EN));
            assertThatThrownBy(() -> match.getName(EN))
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasMessageContaining(MATCH.toString());
            var status = requireNonNull(match.getStatus());
            assertThatThrownBy(status::getHomeScore)
                    .as("its status is not found either")
                    .isInstanceOf(ItemNotFoundException.class)
                    .hasMessageContaining(MATCH.toString());
            assertThatThrownBy(status::getStatus).isInstanceOf(ItemNotFoundException.class);
            assertThatThrownBy(status::getWinnerId).isInstanceOf(ItemNotFoundException.class);
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(SUMMARY_EN, 200, "<match_summary generated_at=\"2026-08-26T12:00:00\"/>");
            Match match = world.entities.match(MATCH, List.of(EN));
            assertThat(match.getName(EN)).isNull();
            assertThat(requireNonNull(match.getStatus()).getHomeScore())
                    .as("null, not 0: there is no such match")
                    .isNull();
        }
    }

    @Test
    void theStatusReadsTheSummaryUntilTheFeedWritesIt() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            var status =
                    requireNonNull(world.entities.match(MATCH, List.of(EN, DE)).getStatus());
            assertThat(world.api.requests()).as("built, not loaded").isEmpty();
            assertThat(status.getStatus()).isEqualTo(EventStatus.Finished);
            assertThat(status.getHomeScore()).isEqualTo(3.0);
            assertThat(status.getAwayScore()).isEqualTo(2.0);
            assertThat(status.getPeriodScores())
                    .extracting(PeriodScore::getPeriodNumber)
                    .containsExactly(1, 2, 3, 4, 5);
            assertThat(status.getMatchStatusId()).isEqualTo(1);
            assertThat(status.isScoreboardAvailable()).isTrue();
            assertThat(status.getScoreboard()).isNull();
            assertThat(status.getWinnerId()).isEqualTo(HOME);
            assertThat(status.getProperties()).isEmpty();
            assertThat(requireNonNull(status.getMatchStatus()).getDescription(DE))
                    .as("described in every locale of the match")
                    .isEqualTo("Beendet");
            assertThat(requireNonNull(status.getMatchStatus(EN)).getDescription())
                    .isEqualTo("Ended");
            assertThat(world.api.requests("GET", SUMMARY_EN)).hasSize(1);

            var live = new OFSportEventStatus();
            live.setStatus(OFEventStatus.LIVE);
            live.setHomeScore(4.0);
            world.matches.oddsChange(MATCH, 1, 1_000, Duration.ZERO, world.time.instant(), live);
            assertThat(status.getStatus()).as("read anew: the feed's").isEqualTo(EventStatus.Live);
            assertThat(status.getHomeScore()).isEqualTo(4.0);
            assertThat(status.getAwayScore()).as("left out: kept").isEqualTo(2.0);
            assertThat(status.getWinnerId())
                    .as("the summary's: the message has none")
                    .isEqualTo(HOME);
        }
    }

    @Test
    void aSettlementRightAfterTheClosingOddsChangeReadsTheFeedsWinner() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 200, Fixtures.replace(SUMMARY, " winner_id=\"od:competitor:47214\"", ""));
            var status = requireNonNull(world.entities.match(MATCH, List.of(EN)).getStatus());
            assertThat(status.getWinnerId()).as("loaded before the match ended").isNull();

            var closing = new OFSportEventStatus();
            closing.setStatus(OFEventStatus.FINALIZED);
            closing.setWinnerId(AWAY.toString());
            world.matches.oddsChange(MATCH, 1, 1_000, Duration.ZERO, world.time.instant(), closing);
            // a settlement carries no status: its callback reads what the odds change wrote
            var settled =
                    requireNonNull(world.entities.match(MATCH, List.of(EN)).getStatus());
            assertThat(settled.getWinnerId()).isEqualTo(AWAY);
            assertThat(settled.getStatus()).isEqualTo(EventStatus.Finished);
            assertThat(world.api.requests("GET", SUMMARY_EN))
                    .as("the summary is not asked again for it")
                    .hasSize(1);
        }
    }

    @Test
    void aStatusNeverSentReadsAs0xDidAndAFailedOneFollowsTheStrategy() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(
                    SUMMARY_EN, 200, SUMMARY.replaceAll("(?s)<sport_event_status.*</sport_event_status>", ""));
            var status = requireNonNull(world.entities.match(MATCH, List.of(EN)).getStatus());
            assertThat(status.getStatus()).isNull();
            assertThat(status.getHomeScore()).isEqualTo(0.0);
            assertThat(status.getPeriodScores()).isEmpty();
            assertThat(status.isScoreboardAvailable()).isFalse();
            assertThat(status.getMatchStatus()).isNull();
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(SUMMARY_EN, 500, "");
            var status = requireNonNull(world.entities.match(MATCH, List.of(EN)).getStatus());
            assertThat(status.getStatus()).isNull();
            assertThat(status.getHomeScore())
                    .as("null, not 0: it could not load")
                    .isNull();
            assertThat(status.getPeriodScores()).isNull();
            assertThat(status.isScoreboardAvailable()).isFalse();
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            world.api.respond(SUMMARY_EN, 500, "");
            var status = requireNonNull(world.entities.match(MATCH, List.of(EN)).getStatus());
            assertThatThrownBy(status::getStatus).isInstanceOf(ApiException.class);
            assertThatThrownBy(status::isScoreboardAvailable).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void theFixtureIsLoadedOnceInTheDefaultLocale() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.THROW)) {
            var fixture =
                    requireNonNull(world.entities.match(MATCH, List.of(DE)).getFixture());
            assertThat(world.api.requests()).as("built, not loaded").isEmpty();
            assertThat(fixture.getStartTime()).isEqualTo(Date.from(Instant.parse("2026-08-26T18:00:00Z")));
            assertThat(fixture.getExtraInfo()).containsEntry("sport_format", "esports");
            assertThat(fixture.getTvChannels())
                    .extracting(TvChannel::getName, TvChannel::getStreamUrl, TvChannel::getLanguage)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Twitch EN", "https://example.invalid/stream", "en"));
            assertThat(world.api.requests("GET", FIXTURE_EN)).hasSize(1);
        }
    }

    @Test
    void aFixtureWithoutExtraInfoOrChannelsHasNoneAndAFailedOneFollowsTheStrategy() {
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(
                    FIXTURE_EN,
                    200,
                    Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml")
                            .replaceAll("(?s)<extra_info>.*</extra_info>", "")
                            .replaceAll("(?s)<tv_channels>.*</tv_channels>", ""));
            var fixture =
                    requireNonNull(world.entities.match(MATCH, List.of(EN)).getFixture());
            assertThat(fixture.getExtraInfo()).isEmpty();
            assertThat(fixture.getTvChannels()).isEmpty();
        }
        try (var world = EntityWorld.start(ExceptionHandlingStrategy.CATCH)) {
            world.api.respond(FIXTURE_EN, 500, "");
            var fixture =
                    requireNonNull(world.entities.match(MATCH, List.of(EN)).getFixture());
            assertThat(fixture.getTvChannels()).isNull();
            assertThat(fixture.getStartTime()).isNull();
        }
    }

    @Test
    void anEagerPreloadLoadsTheMatchInEachLocaleAndItsCompetitors() throws Exception {
        try (var world = EntityWorld.warm(ExceptionHandlingStrategy.THROW)) {
            world.matches.preload(MATCH, List.of(EN, DE));
            world.api.awaitRequest("GET", SUMMARY_EN);
            world.api.awaitRequest("GET", SUMMARY_DE);
            world.api.awaitRequest("GET", HOME_EN);
            world.api.awaitRequest("GET", AWAY_EN);
            world.api.awaitRequest("GET", "/v1/sports/de/competitors/od:competitor:47214/profile");
            world.api.awaitQuiet();
            int before = world.api.requests().size();
            Match match = world.entities.match(MATCH, List.of(EN, DE));
            assertThat(match.getName(DE)).isNotNull();
            assertThat(requireNonNull(match.getCompetitors())).hasSize(2);
            assertThat(world.api.requests()).as("all of it warm").hasSize(before);
        }
    }

    /** The summary with its competitors in the other order: away first. */
    private static String swapped(String summary) {
        String home =
                "<competitor id=\"od:competitor:47214\" name=\"Team Alpha\" abbreviation=\"TA\" underage=\"-1\" qualifier=\"home\"/>";
        String away =
                "<competitor id=\"od:competitor:47215\" name=\"Team Beta\" abbreviation=\"TB\" underage=\"-1\" qualifier=\"away\"/>";
        assertThat(summary).contains(home, away);
        return summary.replace(home, "@HOME@").replace(away, home).replace("@HOME@", away);
    }

    private static String withExtraInfo(String summary, String sportFormat) {
        return summary.replace(
                "</competitors>\n    </sport_event>",
                "</competitors>\n        <extra_info><info key=\"sport_format\" value=\"" + sportFormat
                        + "\"/></extra_info>\n    </sport_event>");
    }
}
