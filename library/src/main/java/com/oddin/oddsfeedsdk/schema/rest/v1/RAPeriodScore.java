package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "periodScore"
)
public class RAPeriodScore extends RAPeriodScoreBase {
    @XmlAttribute(
            name = "home_score",
            required = true
    )
    protected double homeScore;

    @XmlAttribute(
            name = "away_score",
            required = true
    )
    protected double awayScore;

    @XmlAttribute(
            name = "match_status_code"
    )
    protected Integer matchStatusCode;

    @XmlAttribute(name = "home_won_rounds")
    protected Integer homeWonRounds;
    @XmlAttribute(name = "away_won_rounds")
    protected Integer awayWonRounds;
    @XmlAttribute(name = "home_kills")
    protected Integer homeKills;
    @XmlAttribute(name = "away_kills")
    protected Integer awayKills;
    @XmlAttribute(name = "home_goals")
    protected Integer homeGoals;
    @XmlAttribute(name = "away_goals")
    protected Integer awayGoals;
    @XmlAttribute(name = "home_points")
    protected Integer homePoints;
    @XmlAttribute(name = "away_points")
    protected Integer awayPoints;

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

    public double getHomeScore() {
        return homeScore;
    }

    public void setHomeScore(double homeScore) {
        this.homeScore = homeScore;
    }

    public double getAwayScore() {
        return awayScore;
    }

    public void setAwayScore(double awayScore) {
        this.awayScore = awayScore;
    }

    public Integer getMatchStatusCode() {
        return matchStatusCode;
    }

    public void setMatchStatusCode(Integer matchStatusCode) {
        this.matchStatusCode = matchStatusCode;
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
}
