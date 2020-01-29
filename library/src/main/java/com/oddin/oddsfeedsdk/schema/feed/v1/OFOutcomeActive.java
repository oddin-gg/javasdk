package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "outcomeActive"
)
@XmlEnum(Integer.class)
public enum OFOutcomeActive {
    @XmlEnumValue("1")
    ACTIVE(1),
    @XmlEnumValue("0")
    INACTIVE(0);

    private final int value;

    OFOutcomeActive(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFOutcomeActive fromValue(int value) {
        OFOutcomeActive[] values = values();
        for (OFOutcomeActive item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}