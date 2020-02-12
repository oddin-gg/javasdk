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
public class RAPITvChannels {
    @XmlElement(
            name = "tv_channel"
    )
    protected List<RAPITvChannel> tvChannel;

    public RAPITvChannels() {
    }

    public List<RAPITvChannel> getTvChannel() {
        if (this.tvChannel == null) {
            this.tvChannel = new ArrayList<RAPITvChannel>();
        }

        return this.tvChannel;
    }
}
