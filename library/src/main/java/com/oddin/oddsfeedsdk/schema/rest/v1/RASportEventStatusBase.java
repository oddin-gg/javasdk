package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "sportEventStatusBase",
        propOrder = {"periodScores"}
)
@XmlSeeAlso({RASportEventStatus.class})
public class RASportEventStatusBase {
    @XmlElement(
            name = "period_scores"
    )
    protected RAPeriodScores periodScores;
    @XmlAttribute(
            name = "status"
    )
    protected String status;
    @XmlAttribute(
            name = "match_status"
    )
    protected String matchStatus;

    @XmlAttribute(
            name = "winner_id"
    )
    protected String winnerId;

    @XmlAttribute(
            name = "period"
    )
    protected Integer period;

    public RAPeriodScores getPeriodScores() {
        return periodScores;
    }

    public void setPeriodScores(RAPeriodScores periodScores) {
        this.periodScores = periodScores;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMatchStatus() {
        return matchStatus;
    }

    public void setMatchStatus(String matchStatus) {
        this.matchStatus = matchStatus;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(String winnerId) {
        this.winnerId = winnerId;
    }

    public Integer getPeriod() {
        return period;
    }

    public void setPeriod(Integer period) {
        this.period = period;
    }

}
