package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "scoreboardType")
public class OFScoreboard {

    @XmlAttribute(name = "match_status_code")
    protected int currentCTTeam;
    @XmlAttribute(name = "home_won_rounds")
    protected int homeWonRounds;
    @XmlAttribute(name = "away_won_rounds")
    protected int awayWonRounds;
    @XmlAttribute(name = "current_round")
    protected int currentRound;
    @XmlAttribute(name = "home_kills")
    protected int homeKills;
    @XmlAttribute(name = "away_kills")
    protected int awayKills;
    @XmlAttribute(name = "home_destroyed_turrets")
    protected int homeDestroyedTurrets;
    @XmlAttribute(name = "away_destroyed_turrets")
    protected int awayDestroyedTurrets;
    @XmlAttribute(name = "home_gold")
    protected int homeGold;
    @XmlAttribute(name = "away_gold")
    protected int awayGold;
    @XmlAttribute(name = "home_destroyed_towers")
    protected int homeDestroyedTowers;
    @XmlAttribute(name = "away_destroyed_towers")
    protected int awayDestroyedTowers;

    public int getCurrentCTTeam() {
        return currentCTTeam;
    }

    public void setCurrentCTTeam(int currentCTTeam) {
        this.currentCTTeam = currentCTTeam;
    }

    public int getHomeWonRounds() {
        return homeWonRounds;
    }

    public void setHomeWonRounds(int homeWonRounds) {
        this.homeWonRounds = homeWonRounds;
    }

    public int getAwayWonRounds() {
        return awayWonRounds;
    }

    public void setAwayWonRounds(int awayWonRounds) {
        this.awayWonRounds = awayWonRounds;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public int getHomeKills() {
        return homeKills;
    }

    public void setHomeKills(int homeKills) {
        this.homeKills = homeKills;
    }

    public int getAwayKills() {
        return awayKills;
    }

    public void setAwayKills(int awayKills) {
        this.awayKills = awayKills;
    }

    public int getHomeDestroyedTurrets() {
        return homeDestroyedTurrets;
    }

    public void setHomeDestroyedTurrets(int homeDestroyedTurrets) {
        this.homeDestroyedTurrets = homeDestroyedTurrets;
    }

    public int getAwayDestroyedTurrets() {
        return awayDestroyedTurrets;
    }

    public void setAwayDestroyedTurrets(int awayDestroyedTurrets) {
        this.awayDestroyedTurrets = awayDestroyedTurrets;
    }

    public int getHomeGold() {
        return homeGold;
    }

    public void setHomeGold(int homeGold) {
        this.homeGold = homeGold;
    }

    public int getAwayGold() {
        return awayGold;
    }

    public void setAwayGold(int awayGold) {
        this.awayGold = awayGold;
    }

    public int getHomeDestroyedTowers() {
        return homeDestroyedTowers;
    }

    public void setHomeDestroyedTowers(int homeDestroyedTowers) {
        this.homeDestroyedTowers = homeDestroyedTowers;
    }

    public int getAwayDestroyedTowers() {
        return awayDestroyedTowers;
    }

    public void setAwayDestroyedTowers(int awayDestroyedTowers) {
        this.awayDestroyedTowers = awayDestroyedTowers;
    }
}
