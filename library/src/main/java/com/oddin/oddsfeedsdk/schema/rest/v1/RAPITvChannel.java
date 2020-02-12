package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import javax.xml.datatype.XMLGregorianCalendar;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "tvChannel"
)
public class RAPITvChannel {
    @XmlAttribute(
            name = "name",
            required = true
    )
    protected String name;
    @XmlAttribute(
            name = "start_time"
    )
    @XmlSchemaType(
            name = "dateTime"
    )
    protected XMLGregorianCalendar startTime;
    @XmlAttribute(
            name = "stream_url"
    )
    protected String streamUrl;

    public RAPITvChannel() {
    }

    public String getName() {
        return this.name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public XMLGregorianCalendar getStartTime() {
        return this.startTime;
    }

    public void setStartTime(XMLGregorianCalendar value) {
        this.startTime = value;
    }

    public String getStreamUrl() {
        return this.streamUrl;
    }

    public void setStreamUrl(String value) {
        this.streamUrl = value;
    }
}
