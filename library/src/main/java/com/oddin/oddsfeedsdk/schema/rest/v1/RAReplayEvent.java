package com.oddin.oddsfeedsdk.schema.rest.v1;


import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "replay_event")
public class RAReplayEvent {
    @XmlAttribute(name = "id")
    protected String id;
    @XmlAttribute(name = "position")
    protected String position;

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
