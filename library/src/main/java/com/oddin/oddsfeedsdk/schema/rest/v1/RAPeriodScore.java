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

}
