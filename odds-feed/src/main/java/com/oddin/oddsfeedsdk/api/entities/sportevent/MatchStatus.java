package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface MatchStatus extends CompetitionStatus {
    @Nullable List<PeriodScore> getPeriodScores();

    @Nullable Integer getMatchStatusId();

    @Nullable LocalizedStaticData getMatchStatus();

    @Nullable LocalizedStaticData getMatchStatus(Locale locale);

    @Nullable Double getHomeScore();

    @Nullable Double getAwayScore();

    boolean isScoreboardAvailable();

    @Nullable Scoreboard getScoreboard();
}
