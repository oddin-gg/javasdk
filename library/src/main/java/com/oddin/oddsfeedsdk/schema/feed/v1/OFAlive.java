//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:57:50 PM CET 
//


package com.oddin.oddsfeedsdk.schema.feed.v1;

import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;

import javax.xml.bind.annotation.*;


/**
 * <p>Java class for anonymous complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;attribute name="product" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *       &lt;attribute name="timestamp" use="required" type="{http://www.w3.org/2001/XMLSchema}long" />
 *       &lt;attribute name="subscribed" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "")
@XmlRootElement(name = "alive")
public class OFAlive implements BasicMessage {

    @XmlAttribute(name = "product", required = true)
    protected int product;
    @XmlAttribute(name = "timestamp", required = true)
    protected long timestamp;
    @XmlAttribute(name = "subscribed", required = true)
    protected int subscribed;

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
     * Gets the value of the subscribed property.
     */
    public int getSubscribed() {
        return subscribed;
    }

    /**
     * Sets the value of the subscribed property.
     */
    public void setSubscribed(int value) {
        this.subscribed = value;
    }

}
