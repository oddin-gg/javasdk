package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "result"
)
@XmlEnum(Integer.class)
public enum OFResult {
    @XmlEnumValue("0")
    LOST(0),
    @XmlEnumValue("1")
    WON(1),
    @XmlEnumValue("-1")
    UNDECIDED_YET(-1);

    private final int value;

    OFResult(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFResult fromValue(int value) {
        OFResult[] values = values();
        for (OFResult item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}
