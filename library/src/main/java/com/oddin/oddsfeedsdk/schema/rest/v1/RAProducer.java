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
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for producer complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="producer">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;attribute name="id" use="required" type="{http://www.w3.org/2001/XMLSchema}long" />
 *       &lt;attribute name="name" use="required" type="{http://www.w3.org/2001/XMLSchema}string" />
 *       &lt;attribute name="description" use="required" type="{http://www.w3.org/2001/XMLSchema}string" />
 *       &lt;attribute name="api_url" use="required" type="{http://www.w3.org/2001/XMLSchema}string" />
 *       &lt;attribute name="active" use="required" type="{http://www.w3.org/2001/XMLSchema}boolean" />
 *       &lt;attribute name="scope" use="required" type="{http://www.w3.org/2001/XMLSchema}string" />
 *       &lt;attribute name="stateful_recovery_window_in_minutes" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "producer")
public class RAProducer {

    @XmlAttribute(name = "id", required = true)
    protected long id;
    @XmlAttribute(name = "name", required = true)
    protected String name;
    @XmlAttribute(name = "description", required = true)
    protected String description;
    @XmlAttribute(name = "api_url", required = true)
    protected String apiUrl;
    @XmlAttribute(name = "active", required = true)
    protected boolean active;
    @XmlAttribute(name = "scope", required = true)
    protected String scope;
    @XmlAttribute(name = "stateful_recovery_window_in_minutes", required = true)
    protected int statefulRecoveryWindowInMinutes;

    /**
     * Gets the value of the id property.
     * 
     */
    public long getId() {
        return id;
    }

    /**
     * Sets the value of the id property.
     * 
     */
    public void setId(long value) {
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
     * Gets the value of the description property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the value of the description property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setDescription(String value) {
        this.description = value;
    }

    /**
     * Gets the value of the apiUrl property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Sets the value of the apiUrl property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setApiUrl(String value) {
        this.apiUrl = value;
    }

    /**
     * Gets the value of the active property.
     * 
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets the value of the active property.
     * 
     */
    public void setActive(boolean value) {
        this.active = value;
    }

    /**
     * Gets the value of the scope property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getScope() {
        return scope;
    }

    /**
     * Sets the value of the scope property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setScope(String value) {
        this.scope = value;
    }

    /**
     * Gets the value of the statefulRecoveryWindowInMinutes property.
     * 
     */
    public int getStatefulRecoveryWindowInMinutes() {
        return statefulRecoveryWindowInMinutes;
    }

    /**
     * Sets the value of the statefulRecoveryWindowInMinutes property.
     * 
     */
    public void setStatefulRecoveryWindowInMinutes(int value) {
        this.statefulRecoveryWindowInMinutes = value;
    }

}