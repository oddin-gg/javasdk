package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
        name = "scheduleEndpoint",
        propOrder = {"sportEvent"}
)
@XmlRootElement(name = "schedule")
public class RAScheduleEndpoint {
    @XmlElement(
            name = "sport_event"
    )
    protected List<RASportEvent> sportEvent;
    @XmlAttribute(
            name = "generated_at"
    )
    @XmlSchemaType(
            name = "dateTime"
    )
    protected XMLGregorianCalendar generatedAt;

    public RAScheduleEndpoint() {
    }

    public List<RASportEvent> getSportEvent() {
        if (this.sportEvent == null) {
            this.sportEvent = new ArrayList<RASportEvent>();
        }

        return this.sportEvent;
    }

    public XMLGregorianCalendar getGeneratedAt() {
        return this.generatedAt;
    }

    public void setGeneratedAt(XMLGregorianCalendar value) {
        this.generatedAt = value;
    }
}
