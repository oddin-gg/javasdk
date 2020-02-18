//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2019.12.15 at 02:55:52 PM CET 
//


package com.oddin.oddsfeedsdk.schema.rest.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for response_code.
 *
 * <p>The following schema fragment specifies the expected content contained within this class.
 * <p>
 * <pre>
 * &lt;simpleType name="response_code">
 *   &lt;restriction base="{http://www.w3.org/2001/XMLSchema}string">
 *     &lt;enumeration value="OK"/>
 *     &lt;enumeration value="CREATED"/>
 *     &lt;enumeration value="ACCEPTED"/>
 *     &lt;enumeration value="FORBIDDEN"/>
 *     &lt;enumeration value="NOT_FOUND"/>
 *     &lt;enumeration value="CONFLICT"/>
 *     &lt;enumeration value="SERVICE_UNAVAILABLE"/>
 *     &lt;enumeration value="NOT_IMPLEMENTED"/>
 *     &lt;enumeration value="MOVED_PERMANENTLY"/>
 *     &lt;enumeration value="BAD_REQUEST"/>
 *   &lt;/restriction>
 * &lt;/simpleType>
 * </pre>
 */
@XmlType(name = "response_code")
@XmlEnum
public enum RAResponseCode {
    OK,
    CREATED,
    ACCEPTED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    SERVICE_UNAVAILABLE,
    NOT_IMPLEMENTED,
    MOVED_PERMANENTLY,
    BAD_REQUEST;

    public String value() {
        return name();
    }

    public static RAResponseCode fromValue(String v) {
        return valueOf(v);
    }

}
