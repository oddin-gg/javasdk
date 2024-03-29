//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:57:50 PM CET 
//


package com.oddin.oddsfeedsdk.schema.feed.v1;

import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;
import com.oddin.oddsfeedsdk.mq.entities.IdMessage;

import javax.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;


/**
 * <p>Java class for anonymous complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="sport_event_status" type="{}sportEventStatus" minOccurs="0"/>
 *         &lt;element name="odds_generation_properties" type="{}oddsGenerationProperties" minOccurs="0"/>
 *         &lt;element name="odds" minOccurs="0">
 *           &lt;complexType>
 *             &lt;complexContent>
 *               &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *                 &lt;sequence>
 *                   &lt;element name="market" type="{}oddsChangeMarket" maxOccurs="unbounded" minOccurs="0"/>
 *                 &lt;/sequence>
 *                 &lt;attribute name="betting_status" type="{http://www.w3.org/2001/XMLSchema}int" />
 *                 &lt;attribute name="betstop_reason" type="{http://www.w3.org/2001/XMLSchema}int" />
 *               &lt;/restriction>
 *             &lt;/complexContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *       &lt;/sequence>
 *       &lt;attGroup ref="{}messageAttributes"/>
 *       &lt;attribute name="odds_change_reason" type="{}oddsChangeReason" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
        "sportEventStatus",
        "odds"
})
@XmlRootElement(name = "odds_change")
public class OFOddsChange implements BasicMessage, IdMessage {

    @XmlElement(name = "sport_event_status")
    protected OFSportEventStatus sportEventStatus;
    protected OFOddsChange.Odds odds;
    @XmlAttribute(name = "product", required = true)
    protected int product;
    @XmlAttribute(name = "event_id", required = true)
    protected String eventId;
    @XmlAttribute(name = "event_ref_id")
    protected String eventRefId;
    @XmlAttribute(name = "timestamp", required = true)
    protected long timestamp;
    @XmlAttribute(name = "request_id")
    protected Long requestId;

    public String getEventRefId() {
        return eventRefId;
    }

    public void setEventRefId(String eventRefId) {
        this.eventRefId = eventRefId;
    }

    /**
     * Gets the value of the sportEventStatus property.
     *
     * @return possible object is
     * {@link OFSportEventStatus }
     */
    public OFSportEventStatus getSportEventStatus() {
        return sportEventStatus;
    }

    /**
     * Sets the value of the sportEventStatus property.
     *
     * @param value allowed object is
     *              {@link OFSportEventStatus }
     */
    public void setSportEventStatus(OFSportEventStatus value) {
        this.sportEventStatus = value;
    }

    /**
     * Gets the value of the odds property.
     *
     * @return possible object is
     * {@link OFOddsChange.Odds }
     */
    public OFOddsChange.Odds getOdds() {
        return odds;
    }

    /**
     * Sets the value of the odds property.
     *
     * @param value allowed object is
     *              {@link OFOddsChange.Odds }
     */
    public void setOdds(OFOddsChange.Odds value) {
        this.odds = value;
    }

    /**
     * Gets the value of the product property.
     */
    public int getProduct() {
        return product;
    }

    /**
     * Sets the value of the product property.
     */
    public void setProduct(int value) {
        this.product = value;
    }

    /**
     * Gets the value of the eventId property.
     *
     * @return possible object is
     * {@link String }
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Sets the value of the eventId property.
     *
     * @param value allowed object is
     *              {@link String }
     */
    public void setEventId(String value) {
        this.eventId = value;
    }

    /**
     * Gets the value of the timestamp property.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the value of the timestamp property.
     */
    public void setTimestamp(long value) {
        this.timestamp = value;
    }

    /**
     * Gets the value of the requestId property.
     *
     * @return possible object is
     * {@link Long }
     */
    public Long getRequestId() {
        return requestId;
    }

    /**
     * Sets the value of the requestId property.
     *
     * @param value allowed object is
     *              {@link Long }
     */
    public void setRequestId(Long value) {
        this.requestId = value;
    }


    /**
     * <p>Java class for anonymous complex type.
     *
     * <p>The following schema fragment specifies the expected content contained within this class.
     *
     * <pre>
     * &lt;complexType>
     *   &lt;complexContent>
     *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
     *       &lt;sequence>
     *         &lt;element name="market" type="{}oddsChangeMarket" maxOccurs="unbounded" minOccurs="0"/>
     *       &lt;/sequence>
     *       &lt;attribute name="betting_status" type="{http://www.w3.org/2001/XMLSchema}int" />
     *       &lt;attribute name="betstop_reason" type="{http://www.w3.org/2001/XMLSchema}int" />
     *     &lt;/restriction>
     *   &lt;/complexContent>
     * &lt;/complexType>
     * </pre>
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "", propOrder = {
            "market"
    })
    public static class Odds {

        protected List<OFOddsChangeMarket> market;
        @XmlAttribute(name = "betting_status")
        protected Integer bettingStatus;
        @XmlAttribute(name = "betstop_reason")
        protected Integer betstopReason;

        /**
         * Gets the value of the market property.
         *
         * <p>
         * This accessor method returns a reference to the live list,
         * not a snapshot. Therefore any modification you make to the
         * returned list will be present inside the JAXB object.
         * This is why there is not a <CODE>set</CODE> method for the market property.
         *
         * <p>
         * For example, to add a new item, do as follows:
         * <pre>
         *    getMarket().add(newItem);
         * </pre>
         *
         *
         * <p>
         * Objects of the following type(s) are allowed in the list
         * {@link OFOddsChangeMarket }
         */
        public List<OFOddsChangeMarket> getMarket() {
            if (market == null) {
                market = new ArrayList<OFOddsChangeMarket>();
            }
            return this.market;
        }

        /**
         * Gets the value of the bettingStatus property.
         *
         * @return possible object is
         * {@link Integer }
         */
        public Integer getBettingStatus() {
            return bettingStatus;
        }

        /**
         * Sets the value of the bettingStatus property.
         *
         * @param value allowed object is
         *              {@link Integer }
         */
        public void setBettingStatus(Integer value) {
            this.bettingStatus = value;
        }

        /**
         * Gets the value of the betstopReason property.
         *
         * @return possible object is
         * {@link Integer }
         */
        public Integer getBetstopReason() {
            return betstopReason;
        }

        /**
         * Sets the value of the betstopReason property.
         *
         * @param value allowed object is
         *              {@link Integer }
         */
        public void setBetstopReason(Integer value) {
            this.betstopReason = value;
        }


    }

}
