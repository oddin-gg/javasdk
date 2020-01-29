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
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlType;
import javax.xml.datatype.XMLGregorianCalendar;


/**
 * <p>Java class for sportEvent complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="sportEvent">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="tournament" type="{http://schemas.oddin.gg/v1}tournament" minOccurs="0"/>
 *         &lt;element name="competitors" type="{http://schemas.oddin.gg/v1}sportEventCompetitors" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attGroup ref="{http://schemas.oddin.gg/v1}sportEventAttributes"/>
 *       &lt;attribute name="liveodds" type="{http://www.w3.org/2001/XMLSchema}string" />
 *       &lt;attribute name="status" type="{http://www.w3.org/2001/XMLSchema}string" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "sportEvent", propOrder = {
    "tournament",
    "competitors"
})
@XmlSeeAlso({
    RAFixture.class
})
public class RASportEvent {

    protected RATournament tournament;
    protected RASportEventCompetitors competitors;
    @XmlAttribute(name = "liveodds")
    protected String liveodds;
    @XmlAttribute(name = "status")
    protected String status;
    @XmlAttribute(name = "id")
    protected String id;
    @XmlAttribute(name = "name")
    protected String name;
    @XmlAttribute(name = "type")
    protected String type;
    @XmlAttribute(name = "scheduled")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar scheduled;
    @XmlAttribute(name = "start_time_tbd")
    protected Boolean startTimeTbd;
    @XmlAttribute(name = "scheduled_end")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar scheduledEnd;

    /**
     * Gets the value of the tournament property.
     * 
     * @return
     *     possible object is
     *     {@link RATournament }
     *     
     */
    public RATournament getTournament() {
        return tournament;
    }

    /**
     * Sets the value of the tournament property.
     * 
     * @param value
     *     allowed object is
     *     {@link RATournament }
     *     
     */
    public void setTournament(RATournament value) {
        this.tournament = value;
    }

    /**
     * Gets the value of the competitors property.
     * 
     * @return
     *     possible object is
     *     {@link RASportEventCompetitors }
     *     
     */
    public RASportEventCompetitors getCompetitors() {
        return competitors;
    }

    /**
     * Sets the value of the competitors property.
     * 
     * @param value
     *     allowed object is
     *     {@link RASportEventCompetitors }
     *     
     */
    public void setCompetitors(RASportEventCompetitors value) {
        this.competitors = value;
    }

    /**
     * Gets the value of the liveodds property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getLiveodds() {
        return liveodds;
    }

    /**
     * Sets the value of the liveodds property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setLiveodds(String value) {
        this.liveodds = value;
    }

    /**
     * Gets the value of the status property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the value of the status property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setStatus(String value) {
        this.status = value;
    }

    /**
     * Gets the value of the id property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the value of the id property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setId(String value) {
        this.id = value;
    }

    /**
     * Gets the value of the name property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the value of the name property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setName(String value) {
        this.name = value;
    }

    /**
     * Gets the value of the type property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the value of the type property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setType(String value) {
        this.type = value;
    }

    /**
     * Gets the value of the scheduled property.
     * 
     * @return
     *     possible object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public XMLGregorianCalendar getScheduled() {
        return scheduled;
    }

    /**
     * Sets the value of the scheduled property.
     * 
     * @param value
     *     allowed object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public void setScheduled(XMLGregorianCalendar value) {
        this.scheduled = value;
    }

    /**
     * Gets the value of the startTimeTbd property.
     * 
     * @return
     *     possible object is
     *     {@link Boolean }
     *     
     */
    public Boolean isStartTimeTbd() {
        return startTimeTbd;
    }

    /**
     * Sets the value of the startTimeTbd property.
     * 
     * @param value
     *     allowed object is
     *     {@link Boolean }
     *     
     */
    public void setStartTimeTbd(Boolean value) {
        this.startTimeTbd = value;
    }

    /**
     * Gets the value of the scheduledEnd property.
     * 
     * @return
     *     possible object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public XMLGregorianCalendar getScheduledEnd() {
        return scheduledEnd;
    }

    /**
     * Sets the value of the scheduledEnd property.
     * 
     * @param value
     *     allowed object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public void setScheduledEnd(XMLGregorianCalendar value) {
        this.scheduledEnd = value;
    }

}
