package com.oddin.oddsfeedsdk.api.entities.sportevent;

import org.jspecify.annotations.Nullable;

public interface PeriodScore {
    String getPeriodType();

    double getHomeScore();

    double getAwayScore();

    int getPeriodNumber();

    int getMatchStatusCode();

    @Nullable Integer getHomeWonRounds();

    @Nullable Integer getAwayWonRounds();

    @Nullable Integer getHomeKills();

    @Nullable Integer getAwayKills();

    @Nullable Integer getHomeGoals();

    @Nullable Integer getAwayGoals();

    @Nullable Integer getHomePoints();

    @Nullable Integer getAwayPoints();

    @Nullable Integer getHomeGames();

    @Nullable Integer getAwayGames();

    @Nullable Integer getHomeRuns();

    @Nullable Integer getAwayRuns();

    @Nullable Integer getHomeWicketsFallen();

    @Nullable Integer getAwayWicketsFallen();

    @Nullable Integer getHomeOversPlayed();

    @Nullable Integer getHomeBallsPlayed();

    @Nullable Integer getAwayOversPlayed();

    @Nullable Integer getAwayBallsPlayed();

    @Nullable Boolean getHomeWonCoinToss();
}
