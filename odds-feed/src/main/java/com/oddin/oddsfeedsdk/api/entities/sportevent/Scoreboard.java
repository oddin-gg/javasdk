package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Live scoreboard of a match. Every value is optional: the feed sends only what the sport has.
 */
public final class Scoreboard {
    private final @Nullable Integer currentCtTeam;
    private final @Nullable Integer homeWonRounds;
    private final @Nullable Integer awayWonRounds;
    private final @Nullable Integer currentRound;
    private final @Nullable Integer homeKills;
    private final @Nullable Integer awayKills;
    private final @Nullable Integer homeDestroyedTurrets;
    private final @Nullable Integer awayDestroyedTurrets;
    private final @Nullable Integer homeGold;
    private final @Nullable Integer awayGold;
    private final @Nullable Integer homeDestroyedTowers;
    private final @Nullable Integer awayDestroyedTowers;
    private final @Nullable Integer homeGoals;
    private final @Nullable Integer awayGoals;
    private final @Nullable Integer time;
    private final @Nullable Integer gameTime;
    private final @Nullable Integer elapsedTime;
    private final @Nullable Integer currentDefenderTeam;
    private final @Nullable Integer homePoints;
    private final @Nullable Integer awayPoints;
    private final @Nullable Integer homeGames;
    private final @Nullable Integer awayGames;
    private final @Nullable Integer remainingGameTime;
    private final @Nullable Integer homeRuns;
    private final @Nullable Integer awayRuns;
    private final @Nullable Integer homeWicketsFallen;
    private final @Nullable Integer awayWicketsFallen;
    private final @Nullable Integer homeOversPlayed;
    private final @Nullable Integer homeBallsPlayed;
    private final @Nullable Integer awayOversPlayed;
    private final @Nullable Integer awayBallsPlayed;
    private final @Nullable Boolean homeWonCoinToss;
    private final @Nullable Boolean homeBatting;
    private final @Nullable Boolean awayBatting;
    private final @Nullable Integer inning;

    public Scoreboard(
            @Nullable Integer currentCtTeam,
            @Nullable Integer homeWonRounds,
            @Nullable Integer awayWonRounds,
            @Nullable Integer currentRound,
            @Nullable Integer homeKills,
            @Nullable Integer awayKills,
            @Nullable Integer homeDestroyedTurrets,
            @Nullable Integer awayDestroyedTurrets,
            @Nullable Integer homeGold,
            @Nullable Integer awayGold,
            @Nullable Integer homeDestroyedTowers,
            @Nullable Integer awayDestroyedTowers,
            @Nullable Integer homeGoals,
            @Nullable Integer awayGoals,
            @Nullable Integer time,
            @Nullable Integer gameTime,
            @Nullable Integer elapsedTime,
            @Nullable Integer currentDefenderTeam,
            @Nullable Integer homePoints,
            @Nullable Integer awayPoints,
            @Nullable Integer homeGames,
            @Nullable Integer awayGames,
            @Nullable Integer remainingGameTime,
            @Nullable Integer homeRuns,
            @Nullable Integer awayRuns,
            @Nullable Integer homeWicketsFallen,
            @Nullable Integer awayWicketsFallen,
            @Nullable Integer homeOversPlayed,
            @Nullable Integer homeBallsPlayed,
            @Nullable Integer awayOversPlayed,
            @Nullable Integer awayBallsPlayed,
            @Nullable Boolean homeWonCoinToss,
            @Nullable Boolean homeBatting,
            @Nullable Boolean awayBatting,
            @Nullable Integer inning) {
        this.currentCtTeam = currentCtTeam;
        this.homeWonRounds = homeWonRounds;
        this.awayWonRounds = awayWonRounds;
        this.currentRound = currentRound;
        this.homeKills = homeKills;
        this.awayKills = awayKills;
        this.homeDestroyedTurrets = homeDestroyedTurrets;
        this.awayDestroyedTurrets = awayDestroyedTurrets;
        this.homeGold = homeGold;
        this.awayGold = awayGold;
        this.homeDestroyedTowers = homeDestroyedTowers;
        this.awayDestroyedTowers = awayDestroyedTowers;
        this.homeGoals = homeGoals;
        this.awayGoals = awayGoals;
        this.time = time;
        this.gameTime = gameTime;
        this.elapsedTime = elapsedTime;
        this.currentDefenderTeam = currentDefenderTeam;
        this.homePoints = homePoints;
        this.awayPoints = awayPoints;
        this.homeGames = homeGames;
        this.awayGames = awayGames;
        this.remainingGameTime = remainingGameTime;
        this.homeRuns = homeRuns;
        this.awayRuns = awayRuns;
        this.homeWicketsFallen = homeWicketsFallen;
        this.awayWicketsFallen = awayWicketsFallen;
        this.homeOversPlayed = homeOversPlayed;
        this.homeBallsPlayed = homeBallsPlayed;
        this.awayOversPlayed = awayOversPlayed;
        this.awayBallsPlayed = awayBallsPlayed;
        this.homeWonCoinToss = homeWonCoinToss;
        this.homeBatting = homeBatting;
        this.awayBatting = awayBatting;
        this.inning = inning;
    }

    public @Nullable Integer getCurrentCtTeam() {
        return currentCtTeam;
    }

    public @Nullable Integer getHomeWonRounds() {
        return homeWonRounds;
    }

    public @Nullable Integer getAwayWonRounds() {
        return awayWonRounds;
    }

    public @Nullable Integer getCurrentRound() {
        return currentRound;
    }

    public @Nullable Integer getHomeKills() {
        return homeKills;
    }

    public @Nullable Integer getAwayKills() {
        return awayKills;
    }

    public @Nullable Integer getHomeDestroyedTurrets() {
        return homeDestroyedTurrets;
    }

    public @Nullable Integer getAwayDestroyedTurrets() {
        return awayDestroyedTurrets;
    }

    public @Nullable Integer getHomeGold() {
        return homeGold;
    }

    public @Nullable Integer getAwayGold() {
        return awayGold;
    }

    public @Nullable Integer getHomeDestroyedTowers() {
        return homeDestroyedTowers;
    }

    public @Nullable Integer getAwayDestroyedTowers() {
        return awayDestroyedTowers;
    }

    public @Nullable Integer getHomeGoals() {
        return homeGoals;
    }

    public @Nullable Integer getAwayGoals() {
        return awayGoals;
    }

    public @Nullable Integer getTime() {
        return time;
    }

    public @Nullable Integer getGameTime() {
        return gameTime;
    }

    public @Nullable Integer getElapsedTime() {
        return elapsedTime;
    }

    public @Nullable Integer getCurrentDefenderTeam() {
        return currentDefenderTeam;
    }

    public @Nullable Integer getHomePoints() {
        return homePoints;
    }

    public @Nullable Integer getAwayPoints() {
        return awayPoints;
    }

    public @Nullable Integer getHomeGames() {
        return homeGames;
    }

    public @Nullable Integer getAwayGames() {
        return awayGames;
    }

    public @Nullable Integer getRemainingGameTime() {
        return remainingGameTime;
    }

    public @Nullable Integer getHomeRuns() {
        return homeRuns;
    }

    public @Nullable Integer getAwayRuns() {
        return awayRuns;
    }

    public @Nullable Integer getHomeWicketsFallen() {
        return homeWicketsFallen;
    }

    public @Nullable Integer getAwayWicketsFallen() {
        return awayWicketsFallen;
    }

    public @Nullable Integer getHomeOversPlayed() {
        return homeOversPlayed;
    }

    public @Nullable Integer getHomeBallsPlayed() {
        return homeBallsPlayed;
    }

    public @Nullable Integer getAwayOversPlayed() {
        return awayOversPlayed;
    }

    public @Nullable Integer getAwayBallsPlayed() {
        return awayBallsPlayed;
    }

    public @Nullable Boolean getHomeWonCoinToss() {
        return homeWonCoinToss;
    }

    public @Nullable Boolean getHomeBatting() {
        return homeBatting;
    }

    public @Nullable Boolean getAwayBatting() {
        return awayBatting;
    }

    public @Nullable Integer getInning() {
        return inning;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Scoreboard that)) {
            return false;
        }
        return Objects.equals(currentCtTeam, that.currentCtTeam)
                && Objects.equals(homeWonRounds, that.homeWonRounds)
                && Objects.equals(awayWonRounds, that.awayWonRounds)
                && Objects.equals(currentRound, that.currentRound)
                && Objects.equals(homeKills, that.homeKills)
                && Objects.equals(awayKills, that.awayKills)
                && Objects.equals(homeDestroyedTurrets, that.homeDestroyedTurrets)
                && Objects.equals(awayDestroyedTurrets, that.awayDestroyedTurrets)
                && Objects.equals(homeGold, that.homeGold)
                && Objects.equals(awayGold, that.awayGold)
                && Objects.equals(homeDestroyedTowers, that.homeDestroyedTowers)
                && Objects.equals(awayDestroyedTowers, that.awayDestroyedTowers)
                && Objects.equals(homeGoals, that.homeGoals)
                && Objects.equals(awayGoals, that.awayGoals)
                && Objects.equals(time, that.time)
                && Objects.equals(gameTime, that.gameTime)
                && Objects.equals(elapsedTime, that.elapsedTime)
                && Objects.equals(currentDefenderTeam, that.currentDefenderTeam)
                && Objects.equals(homePoints, that.homePoints)
                && Objects.equals(awayPoints, that.awayPoints)
                && Objects.equals(homeGames, that.homeGames)
                && Objects.equals(awayGames, that.awayGames)
                && Objects.equals(remainingGameTime, that.remainingGameTime)
                && Objects.equals(homeRuns, that.homeRuns)
                && Objects.equals(awayRuns, that.awayRuns)
                && Objects.equals(homeWicketsFallen, that.homeWicketsFallen)
                && Objects.equals(awayWicketsFallen, that.awayWicketsFallen)
                && Objects.equals(homeOversPlayed, that.homeOversPlayed)
                && Objects.equals(homeBallsPlayed, that.homeBallsPlayed)
                && Objects.equals(awayOversPlayed, that.awayOversPlayed)
                && Objects.equals(awayBallsPlayed, that.awayBallsPlayed)
                && Objects.equals(homeWonCoinToss, that.homeWonCoinToss)
                && Objects.equals(homeBatting, that.homeBatting)
                && Objects.equals(awayBatting, that.awayBatting)
                && Objects.equals(inning, that.inning);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(currentCtTeam);
        result = 31 * result + Objects.hashCode(homeWonRounds);
        result = 31 * result + Objects.hashCode(awayWonRounds);
        result = 31 * result + Objects.hashCode(currentRound);
        result = 31 * result + Objects.hashCode(homeKills);
        result = 31 * result + Objects.hashCode(awayKills);
        result = 31 * result + Objects.hashCode(homeDestroyedTurrets);
        result = 31 * result + Objects.hashCode(awayDestroyedTurrets);
        result = 31 * result + Objects.hashCode(homeGold);
        result = 31 * result + Objects.hashCode(awayGold);
        result = 31 * result + Objects.hashCode(homeDestroyedTowers);
        result = 31 * result + Objects.hashCode(awayDestroyedTowers);
        result = 31 * result + Objects.hashCode(homeGoals);
        result = 31 * result + Objects.hashCode(awayGoals);
        result = 31 * result + Objects.hashCode(time);
        result = 31 * result + Objects.hashCode(gameTime);
        result = 31 * result + Objects.hashCode(elapsedTime);
        result = 31 * result + Objects.hashCode(currentDefenderTeam);
        result = 31 * result + Objects.hashCode(homePoints);
        result = 31 * result + Objects.hashCode(awayPoints);
        result = 31 * result + Objects.hashCode(homeGames);
        result = 31 * result + Objects.hashCode(awayGames);
        result = 31 * result + Objects.hashCode(remainingGameTime);
        result = 31 * result + Objects.hashCode(homeRuns);
        result = 31 * result + Objects.hashCode(awayRuns);
        result = 31 * result + Objects.hashCode(homeWicketsFallen);
        result = 31 * result + Objects.hashCode(awayWicketsFallen);
        result = 31 * result + Objects.hashCode(homeOversPlayed);
        result = 31 * result + Objects.hashCode(homeBallsPlayed);
        result = 31 * result + Objects.hashCode(awayOversPlayed);
        result = 31 * result + Objects.hashCode(awayBallsPlayed);
        result = 31 * result + Objects.hashCode(homeWonCoinToss);
        result = 31 * result + Objects.hashCode(homeBatting);
        result = 31 * result + Objects.hashCode(awayBatting);
        result = 31 * result + Objects.hashCode(inning);
        return result;
    }

    @Override
    public String toString() {
        return "Scoreboard(" + "currentCtTeam=" + currentCtTeam + ", "
                + "homeWonRounds=" + homeWonRounds + ", "
                + "awayWonRounds=" + awayWonRounds + ", "
                + "currentRound=" + currentRound + ", "
                + "homeKills=" + homeKills + ", "
                + "awayKills=" + awayKills + ", "
                + "homeDestroyedTurrets=" + homeDestroyedTurrets + ", "
                + "awayDestroyedTurrets=" + awayDestroyedTurrets + ", "
                + "homeGold=" + homeGold + ", "
                + "awayGold=" + awayGold + ", "
                + "homeDestroyedTowers=" + homeDestroyedTowers + ", "
                + "awayDestroyedTowers=" + awayDestroyedTowers + ", "
                + "homeGoals=" + homeGoals + ", "
                + "awayGoals=" + awayGoals + ", "
                + "time=" + time + ", "
                + "gameTime=" + gameTime + ", "
                + "elapsedTime=" + elapsedTime + ", "
                + "currentDefenderTeam=" + currentDefenderTeam + ", "
                + "homePoints=" + homePoints + ", "
                + "awayPoints=" + awayPoints + ", "
                + "homeGames=" + homeGames + ", "
                + "awayGames=" + awayGames + ", "
                + "remainingGameTime=" + remainingGameTime + ", "
                + "homeRuns=" + homeRuns + ", "
                + "awayRuns=" + awayRuns + ", "
                + "homeWicketsFallen=" + homeWicketsFallen + ", "
                + "awayWicketsFallen=" + awayWicketsFallen + ", "
                + "homeOversPlayed=" + homeOversPlayed + ", "
                + "homeBallsPlayed=" + homeBallsPlayed + ", "
                + "awayOversPlayed=" + awayOversPlayed + ", "
                + "awayBallsPlayed=" + awayBallsPlayed + ", "
                + "homeWonCoinToss=" + homeWonCoinToss + ", "
                + "homeBatting=" + homeBatting + ", "
                + "awayBatting=" + awayBatting + ", "
                + "inning=" + inning + ")";
    }
}
