package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "eventStatus"
)
@XmlEnum(Integer.class)
public enum OFEventStatus {
    @XmlEnumValue("0")
    NOT_STARTED(0),
    @XmlEnumValue("1")
    LIVE(1),
    @XmlEnumValue("2")
    SUSPENDED(2),
    @XmlEnumValue("3")
    ENDED(3),
    @XmlEnumValue("4")
    FINALIZED(4),
    @XmlEnumValue("5")
    CANCELLED(5),
    @XmlEnumValue("6")
    DELAYED(6),
    @XmlEnumValue("7")
    INTERRUPTED(7),
    @XmlEnumValue("8")
    POSTPONED(8),
    @XmlEnumValue("9")
    ABANDONED(9);

    private final int value;

    OFEventStatus(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFEventStatus fromValue(int value) {
        OFEventStatus[] values = values();
        for (OFEventStatus item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}

