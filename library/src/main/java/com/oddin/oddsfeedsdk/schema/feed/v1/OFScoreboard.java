package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "scoreboardType")
public class OFScoreboard {

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
}
