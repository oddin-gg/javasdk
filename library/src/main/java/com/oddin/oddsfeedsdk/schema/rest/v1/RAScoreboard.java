package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "scoreboardType")
public class RAScoreboard {

    @XmlAttribute(name = "current_ct_team")
    protected Integer currentCTTeam;
    @XmlAttribute(name = "home_won_rounds")
    protected Integer homeWonRounds;
    @XmlAttribute(name = "away_won_rounds")
    protected Integer awayWonRounds;
    @XmlAttribute(name = "current_round")
    protected Integer currentRound;
    @XmlAttribute(name = "home_kills")
    protected Integer homeKills;
    @XmlAttribute(name = "away_kills")
    protected Integer awayKills;
    @XmlAttribute(name = "home_destroyed_turrets")
    protected Integer homeDestroyedTurrets;
    @XmlAttribute(name = "away_destroyed_turrets")
    protected Integer awayDestroyedTurrets;
    @XmlAttribute(name = "home_gold")
    protected Integer homeGold;
    @XmlAttribute(name = "away_gold")
    protected Integer awayGold;
    @XmlAttribute(name = "home_destroyed_towers")
    protected Integer homeDestroyedTowers;
    @XmlAttribute(name = "away_destroyed_towers")
    protected Integer awayDestroyedTowers;
    @XmlAttribute(name = "home_goals")
    protected Integer homeGoals;
    @XmlAttribute(name = "away_goals")
    protected Integer awayGoals;
    @XmlAttribute(name = "time")
    protected Integer time;
    @XmlAttribute(name = "game_time")
    protected Integer gameTime;
    @XmlAttribute(name = "current_def_team")
    protected Integer currentDefenderTeam;
    @XmlAttribute(name = "home_points")
    protected Integer homePoints;
    @XmlAttribute(name = "away_points")
    protected Integer awayPoints;
    @XmlAttribute(name = "home_games")
    protected Integer homeGames;
    @XmlAttribute(name = "away_games")
    protected Integer awayGames;
    @XmlAttribute(name = "remaining_game_time")
    protected Integer remainingGameTime;
    @XmlAttribute(name = "home_runs")
    protected Integer homeRuns;
    @XmlAttribute(name = "away_runs")
    protected Integer awayRuns;
    @XmlAttribute(name = "home_wickets_fallen")
    protected Integer homeWicketsFallen;
    @XmlAttribute(name = "away_wickets_fallen")
    protected Integer awayWicketsFallen;
    @XmlAttribute(name = "home_overs_played")
    protected Integer homeOversPlayed;
    @XmlAttribute(name = "home_balls_played")
    protected Integer homeBallsPlayed;
    @XmlAttribute(name = "away_overs_played")
    protected Integer awayOversPlayed;
    @XmlAttribute(name = "away_balls_played")
    protected Integer awayBallsPlayed;
    @XmlAttribute(name = "home_won_coin_toss")
    protected Boolean homeWonCoinToss;
    @XmlAttribute(name = "home_batting")
    protected Boolean homeBatting;
    @XmlAttribute(name = "away_batting")
    protected Boolean awayBatting;
    @XmlAttribute(name = "inning")
    protected Integer inning;

    public Integer getHomeGoals() {
        return homeGoals;
    }

    public void setHomeGoals(Integer homeGoals) {
        this.homeGoals = homeGoals;
    }

    public Integer getAwayGoals() {
        return awayGoals;
    }

    public void setAwayGoals(Integer awayGoals) {
        this.awayGoals = awayGoals;
    }

    public Integer getTime() {
        return time;
    }

    public void setTime(Integer time) {
        this.time = time;
    }

    public Integer getGameTime() {
        return gameTime;
    }

    public void setGameTime(Integer gameTime) {
        this.gameTime = gameTime;
    }

    public Integer getCurrentCTTeam() {
        return currentCTTeam;
    }

    public void setCurrentCTTeam(Integer currentCTTeam) {
        this.currentCTTeam = currentCTTeam;
    }

    public Integer getHomeWonRounds() {
        return homeWonRounds;
    }

    public void setHomeWonRounds(Integer homeWonRounds) {
        this.homeWonRounds = homeWonRounds;
    }

    public Integer getAwayWonRounds() {
        return awayWonRounds;
    }

    public void setAwayWonRounds(Integer awayWonRounds) {
        this.awayWonRounds = awayWonRounds;
    }

    public Integer getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(Integer currentRound) {
        this.currentRound = currentRound;
    }

    public Integer getHomeKills() {
        return homeKills;
    }

    public void setHomeKills(Integer homeKills) {
        this.homeKills = homeKills;
    }

    public Integer getAwayKills() {
        return awayKills;
    }

    public void setAwayKills(Integer awayKills) {
        this.awayKills = awayKills;
    }

    public Integer getHomeDestroyedTurrets() {
        return homeDestroyedTurrets;
    }

    public void setHomeDestroyedTurrets(Integer homeDestroyedTurrets) {
        this.homeDestroyedTurrets = homeDestroyedTurrets;
    }

    public Integer getAwayDestroyedTurrets() {
        return awayDestroyedTurrets;
    }

    public void setAwayDestroyedTurrets(Integer awayDestroyedTurrets) {
        this.awayDestroyedTurrets = awayDestroyedTurrets;
    }

    public Integer getHomeGold() {
        return homeGold;
    }

    public void setHomeGold(Integer homeGold) {
        this.homeGold = homeGold;
    }

    public Integer getAwayGold() {
        return awayGold;
    }

    public void setAwayGold(Integer awayGold) {
        this.awayGold = awayGold;
    }

    public Integer getHomeDestroyedTowers() {
        return homeDestroyedTowers;
    }

    public void setHomeDestroyedTowers(Integer homeDestroyedTowers) {
        this.homeDestroyedTowers = homeDestroyedTowers;
    }

    public Integer getAwayDestroyedTowers() {
        return awayDestroyedTowers;
    }

    public void setAwayDestroyedTowers(Integer awayDestroyedTowers) {
        this.awayDestroyedTowers = awayDestroyedTowers;
    }

    public Integer getCurrentDefenderTeam() {
        return currentDefenderTeam;
    }

    public void setCurrentDefenderTeam(Integer currentDefenderTeam) {
        this.currentDefenderTeam = currentDefenderTeam;
    }

    public Integer getHomePoints() {
        return homePoints;
    }

    public void setHomePoints(Integer homePoints) {
        this.homePoints = homePoints;
    }

    public Integer getAwayPoints() {
        return awayPoints;
    }

    public void setAwayPoints(Integer awayPoints) {
        this.awayPoints = awayPoints;
    }

    public Integer getHomeGames() {
        return homeGames;
    }

    public void setHomeGames(Integer homeGames) {
        this.homeGames = homeGames;
    }

    public Integer getAwayGames() {
        return awayGames;
    }

    public void setAwayGames(Integer awayGames) {
        this.awayGames = awayGames;
    }

    public Integer getRemainingGameTime() {
        return remainingGameTime;
    }

    public void setRemainingGameTime(Integer remainingGameTime) {
        this.remainingGameTime = remainingGameTime;
    }

    public Integer getHomeRuns() {
        return homeRuns;
    }

    public void setHomeRuns(Integer homeRuns) {
        this.homeRuns = homeRuns;
    }

    public Integer getAwayRuns() {
        return awayRuns;
    }

    public void setAwayRuns(Integer awayRuns) {
        this.awayRuns = awayRuns;
    }

    public Integer getHomeWicketsFallen() {
        return homeWicketsFallen;
    }

    public void setHomeWicketsFallen(Integer homeWicketsFallen) {
        this.homeWicketsFallen = homeWicketsFallen;
    }

    public Integer getAwayWicketsFallen() {
        return awayWicketsFallen;
    }

    public void setAwayWicketsFallen(Integer awayWicketsFallen) {
        this.awayWicketsFallen = awayWicketsFallen;
    }

    public Integer getHomeOversPlayed() {
        return homeOversPlayed;
    }

    public void setHomeOversPlayed(Integer homeOversPlayed) {
        this.homeOversPlayed = homeOversPlayed;
    }

    public Integer getHomeBallsPlayed() {
        return homeBallsPlayed;
    }

    public void setHomeBallsPlayed(Integer homeBallsPlayed) {
        this.homeBallsPlayed = homeBallsPlayed;
    }

    public Integer getAwayOversPlayed() {
        return awayOversPlayed;
    }

    public void setAwayOversPlayed(Integer awayOversPlayed) {
        this.awayOversPlayed = awayOversPlayed;
    }

    public Integer getAwayBallsPlayed() {
        return awayBallsPlayed;
    }

    public void setAwayBallsPlayed(Integer awayBallsPlayed) {
        this.awayBallsPlayed = awayBallsPlayed;
    }

    public Boolean getHomeWonCoinToss() {
        return homeWonCoinToss;
    }

    public void setHomeWonCoinToss(Boolean homeWonCoinToss) {
        this.homeWonCoinToss = homeWonCoinToss;
    }

    public Boolean getHomeBatting() {
        return homeBatting;
    }

    public void setHomeBatting(Boolean homeBatting) {
        this.homeBatting = homeBatting;
    }

    public Boolean getAwayBatting() {
        return awayBatting;
    }

    public void setAwayBatting(Boolean awayBatting) {
        this.awayBatting = awayBatting;
    }
    public Integer getInning() {
        return inning;
    }

    public void setInning(Integer inning) {
        this.inning = inning;
    }
}
