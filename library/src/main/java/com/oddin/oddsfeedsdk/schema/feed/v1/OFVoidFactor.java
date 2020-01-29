package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "voidFactor"
)
@XmlEnum(Double.class)
public enum OFVoidFactor {
    @XmlEnumValue("0.5")
    REFUND_HALF(0.5D),
    @XmlEnumValue("1")
    REFUND_FULL(1.0D);

    private final double value;

    OFVoidFactor(double v) {
        this.value = v;
    }

    public double value() {
        return this.value;
    }

    public static OFVoidFactor fromValue(int value) {
        OFVoidFactor[] values = values();
        for (OFVoidFactor item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}
