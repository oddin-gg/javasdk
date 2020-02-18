package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "",
        propOrder = {"event"}
)
@XmlRootElement(name = "replay_set_content")
public class RAReplaySetContent {
    @XmlElement(name = "replay_event")
    protected List<RAReplayEvent> event;

    public List<RAReplayEvent> getEvent() {
        if (this.event == null) {
            this.event = new ArrayList<RAReplayEvent>();
        }

        return this.event;
    }
}
