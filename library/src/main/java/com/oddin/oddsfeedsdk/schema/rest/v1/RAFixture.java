//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:55:52 PM CET 
//


package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;
import javax.xml.datatype.XMLGregorianCalendar;


/**
 * <p>Java class for fixture complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType name="fixture">
 *   &lt;complexContent>
 *     &lt;extension base="{http://schemas.oddin.gg/v1}sportEvent">
 *       &lt;sequence>
 *         &lt;element name="delayed_info" type="{http://schemas.oddin.gg/v1}delayedInfo" minOccurs="0"/>
 *         &lt;element name="extra_info" type="{http://schemas.oddin.gg/v1}extraInfo" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="start_time_confirmed" type="{http://www.w3.org/2001/XMLSchema}boolean" />
 *       &lt;attribute name="start_time" type="{http://www.w3.org/2001/XMLSchema}dateTime" />
 *       &lt;attribute name="next_live_time" type="{http://www.w3.org/2001/XMLSchema}dateTime" />
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "fixture", propOrder = {
        "delayedInfo",
        "tvChannels",
        "extraInfo"
})
public class RAFixture
        extends RASportEvent {

    @XmlElement(name = "delayed_info")
    protected RADelayedInfo delayedInfo;
    @XmlElement(name = "extra_info")
    protected RAExtraInfo extraInfo;
    @XmlAttribute(name = "start_time_confirmed")
    protected Boolean startTimeConfirmed;
    @XmlAttribute(name = "start_time")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar startTime;
    @XmlAttribute(name = "next_live_time")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar nextLiveTime;
    @XmlElement(
            name = "tv_channels"
    )
    protected RAPITvChannels tvChannels;

    /**
     * Gets the value of the delayedInfo property.
     *
     * @return possible object is
     * {@link RADelayedInfo }
     */
    public RADelayedInfo getDelayedInfo() {
        return delayedInfo;
    }

    /**
     * Sets the value of the delayedInfo property.
     *
     * @param value allowed object is
     *              {@link RADelayedInfo }
     */
    public void setDelayedInfo(RADelayedInfo value) {
        this.delayedInfo = value;
    }

    /**
     * Gets the value of the extraInfo property.
     *
     * @return possible object is
     * {@link RAExtraInfo }
     */
    public RAExtraInfo getExtraInfo() {
        return extraInfo;
    }

    /**
     * Sets the value of the extraInfo property.
     *
     * @param value allowed object is
     *              {@link RAExtraInfo }
     */
    public void setExtraInfo(RAExtraInfo value) {
        this.extraInfo = value;
    }

    /**
     * Gets the value of the startTimeConfirmed property.
     *
     * @return possible object is
     * {@link Boolean }
     */
    public Boolean isStartTimeConfirmed() {
        return startTimeConfirmed;
    }

    /**
     * Sets the value of the startTimeConfirmed property.
     *
     * @param value allowed object is
     *              {@link Boolean }
     */
    public void setStartTimeConfirmed(Boolean value) {
        this.startTimeConfirmed = value;
    }

    /**
     * Gets the value of the startTime property.
     *
     * @return possible object is
     * {@link XMLGregorianCalendar }
     */
    public XMLGregorianCalendar getStartTime() {
        return startTime;
    }

    /**
     * Sets the value of the startTime property.
     *
     * @param value allowed object is
     *              {@link XMLGregorianCalendar }
     */
    public void setStartTime(XMLGregorianCalendar value) {
        this.startTime = value;
    }

    /**
     * Gets the value of the nextLiveTime property.
     *
     * @return possible object is
     * {@link XMLGregorianCalendar }
     */
    public XMLGregorianCalendar getNextLiveTime() {
        return nextLiveTime;
    }

    /**
     * Sets the value of the nextLiveTime property.
     *
     * @param value allowed object is
     *              {@link XMLGregorianCalendar }
     */
    public void setNextLiveTime(XMLGregorianCalendar value) {
        this.nextLiveTime = value;
    }

    public RAPITvChannels getTvChannels() {
        return this.tvChannels;
    }

    public void setTvChannels(RAPITvChannels value) {
        this.tvChannels = value;
    }

}
