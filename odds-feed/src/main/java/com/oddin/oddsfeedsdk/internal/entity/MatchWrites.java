package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.*;

import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.LiveWrite;
import com.oddin.oddsfeedsdk.internal.cache.Write;
import com.oddin.oddsfeedsdk.internal.entity.MatchFields.CompetitorRef;
import com.oddin.oddsfeedsdk.internal.entity.MatchFields.PeriodScoreData;
import com.oddin.oddsfeedsdk.internal.entity.MatchFields.TvChannelData;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodScoreType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodscoresType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFScoreboard;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAExtraInfo;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixture;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAInfo;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPeriodScore;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPeriodScores;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScoreboard;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventCompetitors;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeamCompetitor;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATvChannel;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATvChannels;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the API's responses and the feed's live status say about a match, as writes to its caches.
 *
 * <p>An element the response leaves out is a field it does not carry, so the field keeps what it
 * had: the schemas make nearly all of them optional, and a missing optional value means "keep what
 * you have", never "reset". An id that is not a URN is left out the same way, rather than failing
 * the whole response.
 */
final class MatchWrites {

    private MatchWrites() {}

    /** A summary's match fields; its status is the live state's. */
    static Write summary(RASportEvent event, Locale locale) {
        return match(Write.from(SUMMARY, locale), event);
    }

    /** What a fixture or a schedule says of the match: it only fills. */
    static Write fill(Endpoint endpoint, RASportEvent event, Locale locale) {
        return match(Write.from(endpoint, locale), event);
    }

    /** The fixture's own fields. */
    static Write fixture(RAFixture fixture, Locale locale) {
        return Write.from(FIXTURE, locale)
                .put(START_TIME, ApiValues.instant(fixture.getStartTime()))
                .put(FIXTURE_EXTRA_INFO, extraInfo(fixture.getExtraInfo()))
                .put(TV_CHANNELS, tvChannels(fixture.getTvChannels()));
    }

    /**
     * A summary's live status, its winner included: the summary sends the winner whenever there is
     * one, so a status without it retracts it; a winner that is not a URN says nothing of it.
     */
    static LiveWrite live(RASportEventStatus status) {
        RAPeriodScores periods = status.getPeriodScores();
        RAScoreboard scoreboard = status.getScoreboard();
        var write = LiveWrite.of()
                .put(STATUS, EventStatus.fromApiEventStatus(status.getStatus()))
                .put(MATCH_STATUS_ID, status.getMatchStatusCode())
                .put(HOME_SCORE, status.getHomeScore())
                .put(AWAY_SCORE, status.getAwayScore())
                .put(PERIOD_SCORES, periods == null ? null : restPeriods(periods))
                .put(SCOREBOARD, scoreboard == null ? null : scoreboard(scoreboard))
                .put(SCOREBOARD_AVAILABLE, status.getScoreboardAvailableRaw());
        String winner = status.getWinnerId();
        return winner == null ? write.clear(WINNER_ID) : write.put(WINNER_ID, ApiValues.urn(winner));
    }

    /** A summary's winner alone: what it fills while the feed owns the match. */
    static LiveWrite winner(RASportEventStatus status) {
        return LiveWrite.of().put(WINNER_ID, ApiValues.urn(status.getWinnerId()));
    }

    /**
     * A live message's status. The feed sends the winner once the match has one, and not on every
     * message, so a message without it, or with one that is not a URN, keeps the winner held.
     */
    static LiveWrite live(OFSportEventStatus status) {
        OFPeriodscoresType periods = status.getPeriodScores();
        OFScoreboard scoreboard = status.getScoreboard();
        OFEventStatus eventStatus = status.getStatus();
        return LiveWrite.of()
                // a message without the attribute says nothing of the status, which is kept
                .put(STATUS, eventStatus == null ? null : EventStatus.fromFeedEventStatus(eventStatus))
                .put(MATCH_STATUS_ID, status.getMatchStatus())
                .put(HOME_SCORE, status.getHomeScore())
                .put(AWAY_SCORE, status.getAwayScore())
                .put(PERIOD_SCORES, periods == null ? null : feedPeriods(periods))
                .put(SCOREBOARD, scoreboard == null ? null : scoreboard(scoreboard))
                .put(SCOREBOARD_AVAILABLE, status.getScoreboardAvailable())
                .put(WINNER_ID, ApiValues.urn(status.getWinnerId()));
    }

    private static Write match(Write write, RASportEvent event) {
        var tournament = event.getTournament();
        var sport = tournament == null ? null : tournament.getSport();
        return write.put(NAME, event.getName())
                .put(SPORT_ID, sport == null ? null : ApiValues.urn(sport.getId()))
                .put(SCHEDULED, ApiValues.instant(event.getScheduled()))
                .put(SCHEDULED_END, ApiValues.instant(event.getScheduledEnd()))
                .put(LIVE_ODDS, event.getLiveodds())
                .put(COMPETITORS, competitors(event.getCompetitors()))
                .put(TOURNAMENT_ID, tournament == null ? null : ApiValues.urn(tournament.getId()))
                .put(EXTRA_INFO, extraInfo(event.getExtraInfo()));
    }

    private static @Nullable List<CompetitorRef> competitors(@Nullable RASportEventCompetitors competitors) {
        if (competitors == null) {
            return null;
        }
        var refs = new ArrayList<CompetitorRef>();
        for (RATeamCompetitor competitor : competitors.getCompetitor()) {
            URN id = ApiValues.urn(competitor.getId());
            if (id != null) {
                refs.add(new CompetitorRef(id, competitor.getQualifier()));
            }
        }
        return List.copyOf(refs);
    }

    private static @Nullable Map<String, String> extraInfo(@Nullable RAExtraInfo extraInfo) {
        if (extraInfo == null) {
            return null;
        }
        var info = new LinkedHashMap<String, String>();
        for (RAInfo entry : extraInfo.getInfo()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                info.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(info);
    }

    private static @Nullable List<TvChannel> tvChannels(@Nullable RATvChannels channels) {
        if (channels == null) {
            return null;
        }
        var list = new ArrayList<TvChannel>();
        for (RATvChannel channel : channels.getTvChannel()) {
            if (channel.getName() == null) {
                continue;
            }
            // 0.0.x failed the whole fixture on a channel without its optional stream URL
            String streamUrl = channel.getStreamUrl() == null ? "" : channel.getStreamUrl();
            list.add(new TvChannelData(
                    channel.getName(), streamUrl, channel.getLanguage(), ApiValues.instant(channel.getStartTime())));
        }
        return List.copyOf(list);
    }

    /** In period order, as 0.0.x kept them, whatever order the response sent them in. */
    private static List<PeriodScore> byNumber(List<PeriodScore> periods) {
        periods.sort(Comparator.comparingInt(PeriodScore::getPeriodNumber));
        return List.copyOf(periods);
    }

    private static List<PeriodScore> restPeriods(RAPeriodScores periods) {
        var list = new ArrayList<PeriodScore>();
        for (RAPeriodScore p : periods.getPeriodScore()) {
            if (p.getType() == null || p.getNumber() == null || p.getMatchStatusCode() == null) {
                continue;
            }
            list.add(new PeriodScoreData(
                    p.getType(),
                    p.getNumber(),
                    p.getMatchStatusCode(),
                    p.getHomeScore(),
                    p.getAwayScore(),
                    p.getHomeWonRounds(),
                    p.getAwayWonRounds(),
                    p.getHomeKills(),
                    p.getAwayKills(),
                    p.getHomeGoals(),
                    p.getAwayGoals(),
                    p.getHomePoints(),
                    p.getAwayPoints(),
                    p.getHomeGames(),
                    p.getAwayGames(),
                    p.getHomeRuns(),
                    p.getAwayRuns(),
                    p.getHomeWicketsFallen(),
                    p.getAwayWicketsFallen(),
                    p.getHomeOversPlayed(),
                    p.getHomeBallsPlayed(),
                    p.getAwayOversPlayed(),
                    p.getAwayBallsPlayed(),
                    p.getHomeWonCoinToss()));
        }
        return byNumber(list);
    }

    private static List<PeriodScore> feedPeriods(OFPeriodscoresType periods) {
        var list = new ArrayList<PeriodScore>();
        for (OFPeriodScoreType p : periods.getPeriodScore()) {
            if (p.getType() == null) {
                continue;
            }
            list.add(new PeriodScoreData(
                    p.getType(),
                    p.getNumber(),
                    p.getMatchStatusCode(),
                    p.getHomeScore(),
                    p.getAwayScore(),
                    p.getHomeWonRounds(),
                    p.getAwayWonRounds(),
                    p.getHomeKills(),
                    p.getAwayKills(),
                    p.getHomeGoals(),
                    p.getAwayGoals(),
                    p.getHomePoints(),
                    p.getAwayPoints(),
                    p.getHomeGames(),
                    p.getAwayGames(),
                    p.getHomeRuns(),
                    p.getAwayRuns(),
                    p.getHomeWicketsFallen(),
                    p.getAwayWicketsFallen(),
                    p.getHomeOversPlayed(),
                    p.getHomeBallsPlayed(),
                    p.getAwayOversPlayed(),
                    p.getAwayBallsPlayed(),
                    p.getHomeWonCoinToss()));
        }
        return byNumber(list);
    }

    private static Scoreboard scoreboard(RAScoreboard s) {
        return new Scoreboard(
                s.getCurrentCTTeam(),
                s.getHomeWonRounds(),
                s.getAwayWonRounds(),
                s.getCurrentRound(),
                s.getHomeKills(),
                s.getAwayKills(),
                s.getHomeDestroyedTurrets(),
                s.getAwayDestroyedTurrets(),
                s.getHomeGold(),
                s.getAwayGold(),
                s.getHomeDestroyedTowers(),
                s.getAwayDestroyedTowers(),
                s.getHomeGoals(),
                s.getAwayGoals(),
                s.getTime(),
                s.getGameTime(),
                s.getElapsedTime(),
                s.getCurrentDefenderTeam(),
                s.getHomePoints(),
                s.getAwayPoints(),
                s.getHomeGames(),
                s.getAwayGames(),
                s.getRemainingGameTime(),
                s.getHomeRuns(),
                s.getAwayRuns(),
                s.getHomeWicketsFallen(),
                s.getAwayWicketsFallen(),
                s.getHomeOversPlayed(),
                s.getHomeBallsPlayed(),
                s.getAwayOversPlayed(),
                s.getAwayBallsPlayed(),
                s.getHomeWonCoinToss(),
                s.getHomeBatting(),
                s.getAwayBatting(),
                s.getInning());
    }

    private static Scoreboard scoreboard(OFScoreboard s) {
        return new Scoreboard(
                s.getCurrentCTTeam(),
                s.getHomeWonRounds(),
                s.getAwayWonRounds(),
                s.getCurrentRound(),
                s.getHomeKills(),
                s.getAwayKills(),
                s.getHomeDestroyedTurrets(),
                s.getAwayDestroyedTurrets(),
                s.getHomeGold(),
                s.getAwayGold(),
                s.getHomeDestroyedTowers(),
                s.getAwayDestroyedTowers(),
                s.getHomeGoals(),
                s.getAwayGoals(),
                s.getTime(),
                s.getGameTime(),
                s.getElapsedTime(),
                s.getCurrentDefenderTeam(),
                s.getHomePoints(),
                s.getAwayPoints(),
                s.getHomeGames(),
                s.getAwayGames(),
                s.getRemainingGameTime(),
                s.getHomeRuns(),
                s.getAwayRuns(),
                s.getHomeWicketsFallen(),
                s.getAwayWicketsFallen(),
                s.getHomeOversPlayed(),
                s.getHomeBallsPlayed(),
                s.getAwayOversPlayed(),
                s.getAwayBallsPlayed(),
                s.getHomeWonCoinToss(),
                s.getHomeBatting(),
                s.getAwayBatting(),
                s.getInning());
    }
}
