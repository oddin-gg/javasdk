package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "tvChannels",
        propOrder = {"tvChannel"}
)
public class RATvChannels {
    @XmlElement(
            name = "tv_channel"
    )
    protected List<RATvChannel> tvChannel;

    public List<RATvChannel> getTvChannel() {
        if (this.tvChannel == null) {
            this.tvChannel = new ArrayList<RATvChannel>();
        }

        return this.tvChannel;
    }
}
