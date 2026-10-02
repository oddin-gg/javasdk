package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.internal.cache.Endpoint;
import com.oddin.oddsfeedsdk.internal.cache.Field;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The fields of a match, of its fixture and of its live state, and the endpoints that write them,
 * as {@code CACHE-FIELDS.md} has them. The values are kept as the API sends them where the façade
 * gives them a meaning - the live odds availability, the extra info a sport format comes from - so
 * the façade, not the cache, keeps 0.0.x's reading of them.
 */
final class MatchFields {

    // the match, by its summary
    static final Field<String> NAME = Field.localized("name");
    static final Field<URN> SPORT_ID = Field.shared("sport id");
    static final Field<Instant> SCHEDULED = Field.shared("scheduled time");
    static final Field<Instant> SCHEDULED_END = Field.shared("scheduled end time");
    /** The {@code liveodds} attribute as sent; the façade reads its absence as 0.0.x did. */
    static final Field<String> LIVE_ODDS = Field.shared("live odds availability");

    static final Field<List<CompetitorRef>> COMPETITORS = Field.shared("competitors");
    static final Field<URN> TOURNAMENT_ID = Field.shared("tournament id");
    static final Field<Map<String, String>> EXTRA_INFO = Field.shared("extra info");
    /** REST's, though it travels with the live status: a retracted winner goes with the summary. */
    static final Field<URN> WINNER_ID = Field.shared("winner id");

    /**
     * The match summary: the authoritative source of every match field. Only the winner is sent
     * whenever it exists; every other field is optional in the schema, so a summary that leaves
     * one out keeps it.
     */
    static final Endpoint SUMMARY = new Endpoint(
            "match summary",
            Set.of(
                    NAME,
                    SPORT_ID,
                    SCHEDULED,
                    SCHEDULED_END,
                    LIVE_ODDS,
                    COMPETITORS,
                    TOURNAMENT_ID,
                    EXTRA_INFO,
                    WINNER_ID),
            Set.of(WINNER_ID));

    /** The fixture and the schedules describe the match too, and only fill what the summary has not. */
    static final Endpoint FIXTURE_OF_MATCH = new Endpoint("fixture", Set.of(), Set.of());

    static final Endpoint SCHEDULE = new Endpoint("schedule", Set.of(), Set.of());

    // the fixture, a cache of its own
    static final Field<Instant> START_TIME = Field.shared("start time");
    /** The fixture's own extra info, which can differ from the match's; 0.0.x kept both too. */
    static final Field<Map<String, String>> FIXTURE_EXTRA_INFO = Field.shared("fixture extra info");

    static final Field<List<TvChannel>> TV_CHANNELS = Field.shared("tv channels");

    /** The fixture endpoint, authoritative for the fixture's fields; none of them is always sent. */
    static final Endpoint FIXTURE =
            new Endpoint("fixture", Set.of(START_TIME, FIXTURE_EXTRA_INFO, TV_CHANNELS), Set.of());

    // the live state: the feed's while it is live, the summary's once it is quiet
    static final Field<EventStatus> STATUS = Field.shared("status");
    static final Field<Integer> MATCH_STATUS_ID = Field.shared("match status id");
    static final Field<Double> HOME_SCORE = Field.shared("home score");
    static final Field<Double> AWAY_SCORE = Field.shared("away score");
    static final Field<List<PeriodScore>> PERIOD_SCORES = Field.shared("period scores");
    static final Field<Scoreboard> SCOREBOARD = Field.shared("scoreboard");
    static final Field<Boolean> SCOREBOARD_AVAILABLE = Field.shared("scoreboard available");

    private MatchFields() {}

    /** A competitor of a match, in the order the match lists them. */
    record CompetitorRef(URN id, @Nullable String qualifier) {}

    /** A TV channel of a fixture; a channel sent without its stream URL has an empty one. */
    record TvChannelData(
            String name,
            String streamUrl,
            @Nullable String language,
            @Nullable Instant startTime) implements TvChannel {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getStreamUrl() {
            return streamUrl;
        }

        @Override
        public @Nullable String getLanguage() {
            return language;
        }
    }

    /** One period's score, as the feed or the summary sent it. */
    record PeriodScoreData(
            String periodType,
            int periodNumber,
            int matchStatusCode,
            double homeScore,
            double awayScore,
            @Nullable Integer homeWonRounds,
            @Nullable Integer awayWonRounds,
            @Nullable Integer homeKills,
            @Nullable Integer awayKills,
            @Nullable Integer homeGoals,
            @Nullable Integer awayGoals,
            @Nullable Integer homePoints,
            @Nullable Integer awayPoints,
            @Nullable Integer homeGames,
            @Nullable Integer awayGames,
            @Nullable Integer homeRuns,
            @Nullable Integer awayRuns,
            @Nullable Integer homeWicketsFallen,
            @Nullable Integer awayWicketsFallen,
            @Nullable Integer homeOversPlayed,
            @Nullable Integer homeBallsPlayed,
            @Nullable Integer awayOversPlayed,
            @Nullable Integer awayBallsPlayed,
            @Nullable Boolean homeWonCoinToss)
            implements PeriodScore {

        @Override
        public String getPeriodType() {
            return periodType;
        }

        @Override
        public double getHomeScore() {
            return homeScore;
        }

        @Override
        public double getAwayScore() {
            return awayScore;
        }

        @Override
        public int getPeriodNumber() {
            return periodNumber;
        }

        @Override
        public int getMatchStatusCode() {
            return matchStatusCode;
        }

        @Override
        public @Nullable Integer getHomeWonRounds() {
            return homeWonRounds;
        }

        @Override
        public @Nullable Integer getAwayWonRounds() {
            return awayWonRounds;
        }

        @Override
        public @Nullable Integer getHomeKills() {
            return homeKills;
        }

        @Override
        public @Nullable Integer getAwayKills() {
            return awayKills;
        }

        @Override
        public @Nullable Integer getHomeGoals() {
            return homeGoals;
        }

        @Override
        public @Nullable Integer getAwayGoals() {
            return awayGoals;
        }

        @Override
        public @Nullable Integer getHomePoints() {
            return homePoints;
        }

        @Override
        public @Nullable Integer getAwayPoints() {
            return awayPoints;
        }

        @Override
        public @Nullable Integer getHomeGames() {
            return homeGames;
        }

        @Override
        public @Nullable Integer getAwayGames() {
            return awayGames;
        }

        @Override
        public @Nullable Integer getHomeRuns() {
            return homeRuns;
        }

        @Override
        public @Nullable Integer getAwayRuns() {
            return awayRuns;
        }

        @Override
        public @Nullable Integer getHomeWicketsFallen() {
            return homeWicketsFallen;
        }

        @Override
        public @Nullable Integer getAwayWicketsFallen() {
            return awayWicketsFallen;
        }

        @Override
        public @Nullable Integer getHomeOversPlayed() {
            return homeOversPlayed;
        }

        @Override
        public @Nullable Integer getHomeBallsPlayed() {
            return homeBallsPlayed;
        }

        @Override
        public @Nullable Integer getAwayOversPlayed() {
            return awayOversPlayed;
        }

        @Override
        public @Nullable Integer getAwayBallsPlayed() {
            return awayBallsPlayed;
        }

        @Override
        public @Nullable Boolean getHomeWonCoinToss() {
            return homeWonCoinToss;
        }
    }
}
