package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "oddsChangeReason"
)
@XmlEnum(Integer.class)
public enum OFOddsChangeReason {
    @XmlEnumValue("1")
    RISK_ADJUSTMENT_UPDATE(1);

    private final int value;

    OFOddsChangeReason(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFOddsChangeReason fromValue(int value) {
        OFOddsChangeReason[] values = values();
        for (OFOddsChangeReason item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}
