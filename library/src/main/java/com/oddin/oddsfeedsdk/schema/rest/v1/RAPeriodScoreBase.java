package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "periodScoreBase"
)
@XmlSeeAlso({RAPeriodScore.class})
public class RAPeriodScoreBase {

    @XmlAttribute(
            name = "type",
            required = true
    )
    protected String type;

    @XmlAttribute(
            name = "number"
    )
    protected Integer number;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

}
