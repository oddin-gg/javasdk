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
 * <p>Java class for competitorProfileEndpoint complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="competitorProfileEndpoint">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="competitor" type="{http://schemas.oddin.gg/v1}teamExtended"/>
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
@XmlType(name = "competitorProfileEndpoint", propOrder = {
    "competitor"
})
@XmlRootElement(name = "competitor_profile")
public class RACompetitorProfileEndpoint {

    @XmlElement(required = true)
    protected RATeamExtended competitor;
    @XmlAttribute(name = "generated_at")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar generatedAt;

    /**
     * Gets the value of the competitor property.
     * 
     * @return
     *     possible object is
     *     {@link RATeamExtended }
     *     
     */
    public RATeamExtended getCompetitor() {
        return competitor;
    }

    /**
     * Sets the value of the competitor property.
     * 
     * @param value
     *     allowed object is
     *     {@link RATeamExtended }
     *     
     */
    public void setCompetitor(RATeamExtended value) {
        this.competitor = value;
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