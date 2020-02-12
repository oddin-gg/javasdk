package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "replay_set_content",
        namespace = "",
        propOrder = {"event"}
)
@XmlRootElement(name = "replay_set_content")
public class RAReplaySetContent {
    protected List<RAReplayEvent> event;

    public RAReplaySetContent() {
    }

    public List<RAReplayEvent> getEvent() {
        if (this.event == null) {
            this.event = new ArrayList();
        }

        return this.event;
    }
}
