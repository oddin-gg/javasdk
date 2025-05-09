//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2023.10.04 at 11:24:50 PM CEST
//


package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.annotation.Nullable;


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
 *         &lt;element name="player">
 *           &lt;complexType>
 *             &lt;complexContent>
 *               &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *                 &lt;attribute name="id" type="{http://www.w3.org/2001/XMLSchema}string" />
 *                 &lt;attribute name="name" type="{http://www.w3.org/2001/XMLSchema}string" />
 *                 &lt;attribute name="full_name" type="{http://www.w3.org/2001/XMLSchema}string" />
 *               &lt;/restriction>
 *             &lt;/complexContent>
 *           &lt;/complexType>
 *         &lt;/element>
 *       &lt;/sequence>
 *       &lt;attribute name="generated_at" type="{http://www.w3.org/2001/XMLSchema}string" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 *
 *
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {
        "player"
})
@XmlRootElement(name = "player_profile")
public class RAPlayerProfileEndpoint {

    @XmlElement(required = true)
    protected RAPlayerProfileEndpoint.Player player;
    @XmlAttribute(name = "generated_at")
    protected String generatedAt;

    /**
     * Gets the value of the player property.
     *
     * @return
     *     possible object is
     *     {@link RAPlayerProfileEndpoint.Player }
     *
     */
    public RAPlayerProfileEndpoint.Player getPlayer() {
        return player;
    }

    /**
     * Sets the value of the player property.
     *
     * @param value
     *     allowed object is
     *     {@link RAPlayerProfileEndpoint.Player }
     *
     */
    public void setPlayer(RAPlayerProfileEndpoint.Player value) {
        this.player = value;
    }

    /**
     * Gets the value of the generatedAt property.
     *
     * @return
     *     possible object is
     *     {@link String }
     *
     */
    public String getGeneratedAt() {
        return generatedAt;
    }

    /**
     * Sets the value of the generatedAt property.
     *
     * @param value
     *     allowed object is
     *     {@link String }
     *
     */
    public void setGeneratedAt(String value) {
        this.generatedAt = value;
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
     *       &lt;attribute name="id" type="{http://www.w3.org/2001/XMLSchema}string" />
     *       &lt;attribute name="name" type="{http://www.w3.org/2001/XMLSchema}string" />
     *       &lt;attribute name="full_name" type="{http://www.w3.org/2001/XMLSchema}string" />
     *     &lt;/restriction>
     *   &lt;/complexContent>
     * &lt;/complexType>
     * </pre>
     *
     *
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "")
    public static class Player {

        @XmlAttribute(name = "id", required = true)
        protected String id;
        @XmlAttribute(name = "name", required = true)
        protected String name;
        @Nullable
        @XmlAttribute(name = "full_name")
        protected String fullName;
        @XmlAttribute(name = "sport", required = true)
        protected String sportID;

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
         * Gets the value of the fullName property.
         *
         * @return
         *     possible object is
         *     {@link String }
         *
         */
        public @Nullable String getFullName() {
            return fullName;
        }

        /**
         * Sets the value of the fullName property.
         *
         * @param value
         *     allowed object is
         *     {@link String }
         *
         */
        public void setFullName(@Nullable String value) {
            this.fullName = value;
        }

        /**
         * Gets the value of the sportID property.
         *
         * @return
         *     possible object is
         *     {@link String }
         *
         */
        public String getSportID() {
            return sportID;
        }

        /**
         * Sets the value of the sportID property.
         *
         * @param value
         *     allowed object is
         *     {@link String }
         *
         */
        public void setSportID(String value) {
            this.sportID = value;
        }
    }
}
