package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "sportEventStatus"
)
public class RASportEventStatus extends RASportEventStatusBase {
    @XmlAttribute(
            name = "home_score"
    )
    protected Double homeScore;
    @XmlAttribute(
            name = "away_score"
    )
    protected Double awayScore;

    @XmlAttribute(
            name = "match_status_code"
    )
    protected Integer matchStatusCode;

    public Double getHomeScore() {
        return homeScore;
    }

    public void setHomeScore(Double homeScore) {
        this.homeScore = homeScore;
    }

    public Double getAwayScore() {
        return awayScore;
    }

    public void setAwayScore(Double awayScore) {
        this.awayScore = awayScore;
    }

    public Integer getMatchStatusCode() {
        return matchStatusCode;
    }

    public void setMatchStatusCode(Integer matchStatusCode) {
        this.matchStatusCode = matchStatusCode;
    }
}
