package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "marketStatus"
)
@XmlEnum(Integer.class)
public enum OFMarketStatus {
    @XmlEnumValue("1")
    ACTIVE(1),
    @XmlEnumValue("0")
    DEACTIVATED(0),
    @XmlEnumValue("-1")
    SUSPENDED(-1),
    @XmlEnumValue("-2")
    HANDED_OVER(-2),
    @XmlEnumValue("-3")
    SETTLED(-3),
    @XmlEnumValue("-4")
    CANCELLED(-4);

    private final int value;

    OFMarketStatus(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFMarketStatus fromValue(int value) {
        OFMarketStatus[] values = values();
        for (OFMarketStatus item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}

