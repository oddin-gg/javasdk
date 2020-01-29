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
 * <p>Java class for fixturesEndpoint complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="fixturesEndpoint">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="fixture" type="{http://schemas.oddin.gg/v1}fixture"/>
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
@XmlType(name = "fixturesEndpoint", propOrder = {
        "fixture"
})
@XmlRootElement(name = "fixtures_fixture")
public class RAFixturesEndpoint {

    @XmlElement(required = true)
    protected RAFixture fixture;
    @XmlAttribute(name = "generated_at")
    @XmlSchemaType(name = "dateTime")
    protected XMLGregorianCalendar generatedAt;

    /**
     * Gets the value of the fixture property.
     * 
     * @return
     *     possible object is
     *     {@link RAFixture }
     *     
     */
    public RAFixture getFixture() {
        return fixture;
    }

    /**
     * Sets the value of the fixture property.
     * 
     * @param value
     *     allowed object is
     *     {@link RAFixture }
     *     
     */
    public void setFixture(RAFixture value) {
        this.fixture = value;
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
