package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.*;

import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedUrnFormatException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
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

    /**
     * A summary's match fields, its winner included: a status without a winner retracts it, a
     * summary without a status says nothing of it.
     */
    static Write summary(RASportEvent event, @Nullable RASportEventStatus status, Locale locale) {
        Write write = match(Write.from(SUMMARY, locale), event);
        return status == null ? write.unsaid(WINNER_ID) : write.put(WINNER_ID, urn(status.getWinnerId()));
    }

    /** What a fixture or a schedule says of the match: it only fills. */
    static Write fill(Endpoint endpoint, RASportEvent event, Locale locale) {
        return match(Write.from(endpoint, locale), event);
    }

    /** The fixture's own fields. */
    static Write fixture(RAFixture fixture, Locale locale) {
        return Write.from(FIXTURE, locale)
                .put(START_TIME, instant(fixture.getStartTime()))
                .put(FIXTURE_EXTRA_INFO, extraInfo(fixture.getExtraInfo()))
                .put(TV_CHANNELS, tvChannels(fixture.getTvChannels()));
    }

    /** A summary's live status. */
    static LiveWrite live(RASportEventStatus status) {
        RAPeriodScores periods = status.getPeriodScores();
        RAScoreboard scoreboard = status.getScoreboard();
        return LiveWrite.of()
                .put(STATUS, EventStatus.fromApiEventStatus(status.getStatus()))
                .put(MATCH_STATUS_ID, status.getMatchStatusCode())
                .put(HOME_SCORE, status.getHomeScore())
                .put(AWAY_SCORE, status.getAwayScore())
                .put(PERIOD_SCORES, periods == null ? null : restPeriods(periods))
                .put(SCOREBOARD, scoreboard == null ? null : scoreboard(scoreboard))
                .put(SCOREBOARD_AVAILABLE, status.getScoreboardAvailableRaw());
    }

    /** A live message's status. */
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
                .put(SCOREBOARD_AVAILABLE, status.getScoreboardAvailable());
    }

    /** The id of a sport event, or null when it is not a URN. */
    static @Nullable URN urn(@Nullable String id) {
        if (id == null) {
            return null;
        }
        try {
            return URN.parse(id);
        } catch (UnsupportedUrnFormatException notOne) {
            return null;
        }
    }

    /** A time the API sends without a zone is UTC, as 0.0.x read it. */
    static @Nullable Instant instant(@Nullable XMLGregorianCalendar time) {
        if (time == null) {
            return null;
        }
        if (time.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            var utc = (XMLGregorianCalendar) time.clone();
            utc.setTimezone(0);
            return utc.toGregorianCalendar().toInstant();
        }
        return time.toGregorianCalendar().toInstant();
    }

    private static Write match(Write write, RASportEvent event) {
        var tournament = event.getTournament();
        var sport = tournament == null ? null : tournament.getSport();
        return write.put(NAME, event.getName())
                .put(SPORT_ID, sport == null ? null : urn(sport.getId()))
                .put(SCHEDULED, instant(event.getScheduled()))
                .put(SCHEDULED_END, instant(event.getScheduledEnd()))
                .put(LIVE_ODDS, event.getLiveodds())
                .put(COMPETITORS, competitors(event.getCompetitors()))
                .put(TOURNAMENT_ID, tournament == null ? null : urn(tournament.getId()))
                .put(EXTRA_INFO, extraInfo(event.getExtraInfo()));
    }

    private static @Nullable List<CompetitorRef> competitors(@Nullable RASportEventCompetitors competitors) {
        if (competitors == null) {
            return null;
        }
        var refs = new ArrayList<CompetitorRef>();
        for (RATeamCompetitor competitor : competitors.getCompetitor()) {
            URN id = urn(competitor.getId());
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
                    channel.getName(), streamUrl, channel.getLanguage(), instant(channel.getStartTime())));
        }
        return List.copyOf(list);
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
        return List.copyOf(list);
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
        return List.copyOf(list);
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
