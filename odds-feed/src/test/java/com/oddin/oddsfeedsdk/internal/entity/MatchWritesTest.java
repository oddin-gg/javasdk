package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.internal.cache.EntityCache;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.cache.LiveState;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.LiveValues;
import com.oddin.oddsfeedsdk.internal.cache.LiveWrite;
import com.oddin.oddsfeedsdk.internal.cache.Write;
import com.oddin.oddsfeedsdk.internal.entity.MatchFields.CompetitorRef;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixturesEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.datatype.DatatypeFactory;
import org.junit.jupiter.api.Test;

/** What the API's responses and the feed's live status write about a match, from the schema's fixtures. */
class MatchWritesTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final URN MATCH = URN.parse("od:match:198314");

    private final RestDecoder rest = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES);
    private final EntityCache<URN> matches =
            new EntityCache<>("match", 100, Duration.ofHours(12), Duration.ofMinutes(1));
    private final LiveState<URN> live = new LiveState<>(100);

    @Test
    void aSummaryWritesTheMatchItsWinnerAndItsLiveStatus() throws DecodeException {
        var summary = decode("rest/match_summary/match_summary.xml", RAMatchSummaryEndpoint.class);
        matches.writeAuthoritative(
                MATCH,
                MatchWrites.summary(summary.getSportEvent(), summary.getSportEventStatus(), EN),
                matches.stamp(MATCH));
        Entry match = requireNonNull(matches.get(MATCH));
        assertThat(match.get(NAME, EN)).isEqualTo("Team Alpha vs Team Beta");
        assertThat(match.get(SPORT_ID, null)).isEqualTo(URN.parse("od:sport:23"));
        assertThat(match.get(TOURNAMENT_ID, null)).isEqualTo(URN.parse("od:tournament:1042"));
        assertThat(match.get(SCHEDULED, null))
                .as("no zone in the response: UTC, as 0.0.x read it")
                .isEqualTo(Instant.parse("2026-08-26T18:00:00Z"));
        assertThat(match.get(SCHEDULED_END, null)).isNull();
        assertThat(match.get(COMPETITORS, null))
                .containsExactly(
                        new CompetitorRef(URN.parse("od:competitor:47214"), "home"),
                        new CompetitorRef(URN.parse("od:competitor:47215"), "away"));
        assertThat(match.get(WINNER_ID, null)).isEqualTo(URN.parse("od:competitor:47214"));
        assertThat(match.get(LIVE_ODDS, null)).as("as sent: not at all").isNull();

        live.restWriteIfQuiet(MATCH, Instant.now(), MatchWrites.live(summary.getSportEventStatus()), () -> false);
        LiveValues values = requireNonNull(live.get(MATCH));
        assertThat(values.get(STATUS)).isEqualTo(EventStatus.Finished);
        assertThat(values.get(MATCH_STATUS_ID)).isEqualTo(1);
        assertThat(values.get(HOME_SCORE)).isEqualTo(3.0);
        assertThat(values.get(AWAY_SCORE)).isEqualTo(2.0);
        assertThat(values.get(SCOREBOARD_AVAILABLE)).isTrue();
        assertThat(values.get(SCOREBOARD)).as("not in this summary").isNull();
        List<PeriodScore> periods = requireNonNull(values.get(PERIOD_SCORES));
        assertThat(periods).hasSize(5);
        assertThat(periods.getFirst().getPeriodType()).isEqualTo("round");
        assertThat(periods.getFirst().getPeriodNumber()).isEqualTo(1);
        assertThat(periods.getFirst().getHomeScore()).isEqualTo(1.0);
    }

    @Test
    void aRetractedWinnerGoesWithTheNextSummary() throws DecodeException {
        var summary = decode("rest/match_summary/match_summary.xml", RAMatchSummaryEndpoint.class);
        matches.writeAuthoritative(
                MATCH,
                MatchWrites.summary(summary.getSportEvent(), summary.getSportEventStatus(), EN),
                matches.stamp(MATCH));
        summary.getSportEventStatus().setWinnerId(null);
        summary.getSportEvent().setName(null);
        matches.writeAuthoritative(
                MATCH,
                MatchWrites.summary(summary.getSportEvent(), summary.getSportEventStatus(), EN),
                matches.stamp(MATCH));
        Entry match = requireNonNull(matches.get(MATCH));
        assertThat(match.get(WINNER_ID, null))
                .as("always sent when there is one")
                .isNull();
        assertThat(match.get(NAME, EN)).as("optional: left out, kept").isEqualTo("Team Alpha vs Team Beta");
    }

    @Test
    void aWinnerThatIsNotAUrnKeepsTheWinnerHeld() throws DecodeException {
        var summary = decode("rest/match_summary/match_summary.xml", RAMatchSummaryEndpoint.class);
        matches.writeAuthoritative(
                MATCH,
                MatchWrites.summary(summary.getSportEvent(), summary.getSportEventStatus(), EN),
                matches.stamp(MATCH));
        summary.getSportEventStatus().setWinnerId("not a urn");
        matches.writeAuthoritative(
                MATCH,
                MatchWrites.summary(summary.getSportEvent(), summary.getSportEventStatus(), EN),
                matches.stamp(MATCH));
        assertThat(requireNonNull(matches.get(MATCH)).get(WINNER_ID, null))
                .as("an id that is not a URN is left out, not a retraction")
                .isEqualTo(URN.parse("od:competitor:47214"));
    }

    @Test
    void periodScoresAreInPeriodOrderWhateverOrderTheyCameIn() throws DecodeException {
        var summary = decode("rest/match_summary/match_summary.xml", RAMatchSummaryEndpoint.class);
        java.util.Collections.reverse(
                summary.getSportEventStatus().getPeriodScores().getPeriodScore());
        live.restWriteIfQuiet(MATCH, Instant.now(), MatchWrites.live(summary.getSportEventStatus()), () -> false);
        assertThat(requireNonNull(requireNonNull(live.get(MATCH)).get(PERIOD_SCORES)))
                .extracting(PeriodScore::getPeriodNumber)
                .containsExactly(1, 2, 3, 4, 5);

        var message = new com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus();
        var periods = new com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodscoresType();
        for (int number : List.of(3, 1, 2)) {
            var period = new com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodScoreType();
            period.setType("map");
            period.setNumber(number);
            periods.getPeriodScore().add(period);
        }
        message.setPeriodScores(periods);
        var fromFeed = new LiveState<URN>(100);
        fromFeed.feedWriteIfNewer(MATCH, 1, 1, Duration.ZERO, Instant.now(), MatchWrites.live(message));
        assertThat(requireNonNull(requireNonNull(fromFeed.get(MATCH)).get(PERIOD_SCORES)))
                .extracting(PeriodScore::getPeriodNumber)
                .containsExactly(1, 2, 3);
    }

    @Test
    void aCricketSummaryAndLiveMessageCarryTheSameScoreboard() throws DecodeException {
        var summary = decode("rest/match_summary/match_summary_cricket_scoreboard.xml", RAMatchSummaryEndpoint.class);
        live.restWriteIfQuiet(MATCH, Instant.now(), MatchWrites.live(summary.getSportEventStatus()), () -> false);
        var fromRest = requireNonNull(requireNonNull(live.get(MATCH)).get(SCOREBOARD));

        var message = (OFOddsChange) FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES)
                .decode(Fixtures.read("feed/odds_change/odds_change_cricket_scoreboard.xml")
                        .getBytes(UTF_8));
        var other = new LiveState<URN>(100);
        other.feedWriteIfNewer(
                MATCH, 3, 1, Duration.ZERO, Instant.now(), MatchWrites.live(message.getSportEventStatus()));
        LiveValues fromFeed = requireNonNull(other.get(MATCH));
        var scoreboard = requireNonNull(fromFeed.get(SCOREBOARD));

        // the summary's fixture has the match clock too, the message's not
        assertThat(scoreboard)
                .usingRecursiveComparison()
                .ignoringFields("time", "gameTime", "elapsedTime")
                .isEqualTo(fromRest);
        assertThat(fromRest.getTime()).isEqualTo(1800);
        assertThat(fromRest.getGameTime()).isEqualTo(1800);
        assertThat(fromRest.getElapsedTime()).isEqualTo(1800);
        assertThat(scoreboard.getTime()).isNull();
        assertThat(scoreboard.getHomeRuns()).isEqualTo(156);
        assertThat(scoreboard.getAwayWicketsFallen()).isEqualTo(6);
        assertThat(scoreboard.getHomeBallsPlayed()).isEqualTo(3);
        assertThat(scoreboard.getAwayOversPlayed()).isEqualTo(18);
        assertThat(scoreboard.getHomeWonCoinToss()).isTrue();
        assertThat(scoreboard.getAwayBatting()).isFalse();
        assertThat(scoreboard.getInning()).isEqualTo(1);
        assertThat(fromFeed.get(STATUS)).isEqualTo(EventStatus.Live);
        assertThat(fromFeed.get(MATCH_STATUS_ID)).isZero();
        PeriodScore inning = requireNonNull(fromFeed.get(PERIOD_SCORES)).getFirst();
        assertThat(inning.getHomeRuns()).isEqualTo(156);
        assertThat(inning.getAwayOversPlayed()).isEqualTo(18);
        assertThat(inning.getHomeWonCoinToss()).isTrue();
        assertThat(inning)
                .as("the same period, read from either side")
                .isEqualTo(requireNonNull(requireNonNull(live.get(MATCH)).get(PERIOD_SCORES))
                        .getFirst());
    }

    @Test
    void aLiveMessageWithoutScoresKeepsThose() {
        var message = new com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus();
        message.setStatus(com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus.LIVE);
        live.restWriteIfQuiet(
                MATCH, Instant.now(), LiveWrite.of().put(HOME_SCORE, 1.0).put(AWAY_SCORE, 0.0), () -> false);
        live.feedWriteIfNewer(MATCH, 1, 1, Duration.ZERO, Instant.now(), MatchWrites.live(message));
        LiveValues values = requireNonNull(live.get(MATCH));
        assertThat(values.get(STATUS)).isEqualTo(EventStatus.Live);
        assertThat(values.get(HOME_SCORE))
                .as("a missing optional score keeps what it had")
                .isEqualTo(1.0);
        assertThat(values.get(PERIOD_SCORES)).isNull();
    }

    @Test
    void periodScoresAndAScoreboardLeftOutAreKeptFromEitherSide() throws DecodeException {
        var cricket = decode("rest/match_summary/match_summary_cricket_scoreboard.xml", RAMatchSummaryEndpoint.class);
        live.restWriteIfQuiet(MATCH, Instant.now(), MatchWrites.live(cricket.getSportEventStatus()), () -> false);
        var before = requireNonNull(live.get(MATCH));

        var bare = new com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus();
        bare.setStatus("live");
        live.restWriteIfQuiet(MATCH, Instant.now(), MatchWrites.live(bare), () -> false);
        var feedBare = new com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus();
        live.feedWriteIfNewer(MATCH, 1, 1, Duration.ZERO, Instant.now(), MatchWrites.live(feedBare));

        var after = requireNonNull(live.get(MATCH));
        assertThat(after.get(PERIOD_SCORES))
                .isEqualTo(before.get(PERIOD_SCORES))
                .isNotEmpty();
        assertThat(after.get(SCOREBOARD)).isEqualTo(before.get(SCOREBOARD)).isNotNull();
        assertThat(after.get(STATUS))
                .as("a feed status without its attribute says nothing of it")
                .isEqualTo(EventStatus.Live);
    }

    @Test
    void aFixtureWritesItsOwnFieldsAndFillsTheMatch() throws DecodeException {
        var fixture = decode("rest/fixtures_fixture/fixtures_fixture.xml", RAFixturesEndpoint.class)
                .getFixture();
        var fixtures = new EntityCache<URN>("fixture", 100, Duration.ofHours(12), Duration.ofMinutes(1));
        fixtures.writeAuthoritative(MATCH, MatchWrites.fixture(fixture, EN), fixtures.stamp(MATCH));
        Entry entry = requireNonNull(fixtures.get(MATCH));
        assertThat(entry.get(START_TIME, null)).isEqualTo(Instant.parse("2026-08-26T18:00:00Z"));
        assertThat(entry.get(FIXTURE_EXTRA_INFO, null)).containsExactly(Map.entry("sport_format", "esports"));
        List<TvChannel> channels = requireNonNull(entry.get(TV_CHANNELS, null));
        assertThat(channels).hasSize(1);
        assertThat(channels.getFirst().getName()).isEqualTo("Twitch EN");
        assertThat(channels.getFirst().getStreamUrl()).isEqualTo("https://example.invalid/stream");
        assertThat(channels.getFirst().getLanguage()).isEqualTo("en");

        fixture.getTvChannels().getTvChannel().getFirst().setStreamUrl(null);
        fixtures.writeAuthoritative(MATCH, MatchWrites.fixture(fixture, EN), fixtures.stamp(MATCH));
        assertThat(requireNonNull(requireNonNull(fixtures.get(MATCH)).get(TV_CHANNELS, null))
                        .getFirst()
                        .getStreamUrl())
                .as("optional in the schema: empty, where 0.0.x failed the whole fixture")
                .isEmpty();

        matches.fill(MATCH, MatchWrites.fill(FIXTURE_OF_MATCH, fixture, EN), matches.stamp(MATCH));
        Entry match = requireNonNull(matches.get(MATCH));
        assertThat(match.get(NAME, EN)).isEqualTo("Team Alpha vs Team Beta");
        assertThat(match.get(SCHEDULED_END, null)).isEqualTo(Instant.parse("2026-08-26T20:00:00Z"));
        assertThat(match.get(EXTRA_INFO, null)).containsEntry("sport_format", "esports");
        assertThat(match.isAuthoritative(NAME, EN)).as("a fill marks nothing").isFalse();
    }

    @Test
    void aScheduleFillsEachMatchItLists() throws DecodeException {
        var schedule = decode("rest/schedule/schedule.xml", RAScheduleEndpoint.class);
        var second = schedule.getSportEvent().get(1);
        Write fill = MatchWrites.fill(SCHEDULE, second, EN);
        URN id = requireNonNull(ApiValues.urn(second.getId()));
        matches.fill(id, fill, matches.stamp(id));
        Entry match = requireNonNull(matches.get(id));
        assertThat(match.get(NAME, EN)).isEqualTo("Team Gamma vs Team Delta");
        assertThat(match.get(LIVE_ODDS, null)).isEqualTo("booked");
    }

    @Test
    void anIdThatIsNotAUrnIsLeftOutAndATimeWithAZoneKeepsIt() throws Exception {
        assertThat(ApiValues.urn("not an urn")).isNull();
        assertThat(ApiValues.urn(null)).isNull();
        var withZone = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-08-26T18:00:00+02:00");
        assertThat(ApiValues.instant(withZone)).isEqualTo(Instant.parse("2026-08-26T16:00:00Z"));
    }

    private <T> T decode(String fixture, Class<T> type) throws DecodeException {
        return rest.decode(Fixtures.read(fixture).getBytes(UTF_8), type);
    }
}
