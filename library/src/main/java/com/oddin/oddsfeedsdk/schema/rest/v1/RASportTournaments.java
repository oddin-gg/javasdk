//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:55:52 PM CET 
//


package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.*;
import javax.xml.datatype.XMLGregorianCalendar;


/**
 * <p>Java class for sportTournaments complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="sportTournaments">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="sport" type="{http://schemas.oddin.gg/v1}sport"/>
 *         &lt;element name="tournaments" type="{http://schemas.oddin.gg/v1}tournaments" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="generated_at" type="{http://www.w3.org/2001/XMLSchema}dateTime" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "sportTournaments", propOrder = {
        "sport",
    "tournaments"
})
@XmlRootElement(name="sport_tournaments")
public class RASportTournaments {

    @XmlElement(required = true)
    protected RASport sport;
    protected RATournaments tournaments;
    @XmlAttribute(name = "generated_at")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar generatedAt;

    /**
     * Gets the value of the sport property.
     * 
     * @return
     *     possible object is
     *     {@link RASport }
     *     
     */
    public RASport getSport() {
        return sport;
    }

    /**
     * Sets the value of the sport property.
     * 
     * @param value
     *     allowed object is
     *     {@link RASport }
     *     
     */
    public void setSport(RASport value) {
        this.sport = value;
    }

    /**
     * Gets the value of the tournaments property.
     * 
     * @return
     *     possible object is
     *     {@link RATournaments }
     *     
     */
    public RATournaments getTournaments() {
        return tournaments;
    }

    /**
     * Sets the value of the tournaments property.
     * 
     * @param value
     *     allowed object is
     *     {@link RATournaments }
     *     
     */
    public void setTournaments(RATournaments value) {
        this.tournaments = value;
    }

    /**
     * Gets the value of the generatedAt property.
     * 
     * @return
     *     possible object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public XMLGregorianCalendar getGeneratedAt() {
        return generatedAt;
    }

    /**
     * Sets the value of the generatedAt property.
     * 
     * @param value
     *     allowed object is
     *     {@link XMLGregorianCalendar }
     *     
     */
    public void setGeneratedAt(XMLGregorianCalendar value) {
        this.generatedAt = value;
    }

}