package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "replay_event",
        namespace = "",
        propOrder = {"value"}
)
public class RAReplayEvent {
    @XmlValue
    protected String value;
    @XmlAttribute(
            name = "id"
    )
    protected String id;
    @XmlAttribute(
            name = "position"
    )
    protected String position;

    public RAReplayEvent() {
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public String getPosition() {
        return this.position;
    }

    public void setPosition(String value) {
        this.position = value;
    }
}
