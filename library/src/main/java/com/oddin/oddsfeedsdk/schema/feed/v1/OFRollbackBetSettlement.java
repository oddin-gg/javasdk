package com.oddin.oddsfeedsdk.schema.feed.v1;


import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;

import javax.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
        "market"
})
@XmlRootElement(name = "rollback_bet_settlement")
public class OFRollbackBetSettlement implements BasicMessage {

    @XmlElement(required = true)
    protected List<OFRollbackBetSettlementMarket> market;
    @XmlAttribute(name = "product", required = true)
    protected int product;
    @XmlAttribute(name = "event_id", required = true)
    protected String eventId;
    @XmlAttribute(name = "timestamp", required = true)
    protected long timestamp;

    public List<OFRollbackBetSettlementMarket> getMarket() {
        if (market == null) {
            market = new ArrayList<>();
        }
        return this.market;
    }

    /**
     * Gets the value of the product property.
     *
     */
    public int getProduct() {
        return product;
    }

    /**
     * Sets the value of the product property.
     *
     */
    public void setProduct(int value) {
        this.product = value;
    }

    /**
     * Gets the value of the eventId property.
     *
     * @return
     *     possible object is
     *     {@link String }
     *
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Sets the value of the eventId property.
     *
     * @param value
     *     allowed object is
     *     {@link String }
     *
     */
    public void setEventId(String value) {
        this.eventId = value;
    }

    /**
     * Gets the value of the timestamp property.
     *
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the value of the timestamp property.
     *
     */
    public void setTimestamp(long value) {
        this.timestamp = value;
    }
}
