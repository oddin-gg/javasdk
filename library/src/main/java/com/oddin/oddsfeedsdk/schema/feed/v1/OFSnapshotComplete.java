//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:57:50 PM CET 
//


package com.oddin.oddsfeedsdk.schema.feed.v1;


import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for anonymous complex type.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;attribute name="request_id" use="required" type="{http://www.w3.org/2001/XMLSchema}long" />
 *       &lt;attribute name="product" use="required" type="{http://www.w3.org/2001/XMLSchema}int" />
 *       &lt;attribute name="timestamp" use="required" type="{http://www.w3.org/2001/XMLSchema}long" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "")
@XmlRootElement(name = "snapshot_complete")
public class OFSnapshotComplete implements BasicMessage {

    @XmlAttribute(name = "request_id", required = true)
    protected long requestId;
    @XmlAttribute(name = "product", required = true)
    protected int product;
    @XmlAttribute(name = "timestamp", required = true)
    protected long timestamp;

    /**
     * Gets the value of the requestId property.
     */
    public long getRequestId() {
        return requestId;
    }

    /**
     * Sets the value of the requestId property.
     */
    public void setRequestId(long value) {
        this.requestId = value;
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

}