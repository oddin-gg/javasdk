package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "periodScores",
        propOrder = {"periodScore"}
)
public class RAPeriodScores {
    @XmlElement(
            name = "period_score"
    )
    protected List<RAPeriodScore> periodScore;

    public List<RAPeriodScore> getPeriodScore() {
        if (this.periodScore == null) {
            this.periodScore = new ArrayList<RAPeriodScore>();
        }

        return this.periodScore;
    }
}
