package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "changeType"
)
@XmlEnum(Integer.class)
public enum OFChangeType {
    @XmlEnumValue("1")
    NEW(1),
    @XmlEnumValue("2")
    DATETIME(2),
    @XmlEnumValue("3")
    CANCELLED(3),
    @XmlEnumValue("4")
    FORMAT(4),
    @XmlEnumValue("5")
    COVERAGE(5),
    @XmlEnumValue("106")
    STREAM_URL(106);

    private final int value;

    OFChangeType(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFChangeType fromValue(int value) {
        OFChangeType[] values = values();
        for (OFChangeType item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}

