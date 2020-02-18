package com.oddin.oddsfeedsdk.schema.rest.v1;

import com.oddin.oddsfeedsdk.api.ResponseWithCode;

import javax.xml.bind.annotation.*;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "response")
public class RAError implements ResponseWithCode {

    @XmlElement(name = "action")
    protected RAErrorAction action;
    @XmlElement(name = "message")
    protected RAErrorMessage message;
    @XmlAttribute(name = "response_code")
    protected RAResponseCode responseCode;

    /**
     * Gets the value of the responseCode property.
     *
     * @return possible object is
     * {@link RAResponseCode }
     */
    public RAResponseCode getResponseCode() {
        return responseCode;
    }

    /**
     * Sets the value of the responseCode property.
     *
     * @param value allowed object is
     *              {@link RAResponseCode }
     */
    public void setResponseCode(RAResponseCode value) {
        this.responseCode = value;
    }

    public RAErrorAction getAction() {
        return action;
    }

    public void setAction(RAErrorAction action) {
        this.action = action;
    }

    public RAErrorMessage getMessage() {
        return message;
    }

    public void setMessage(RAErrorMessage message) {
        this.message = message;
    }
}

