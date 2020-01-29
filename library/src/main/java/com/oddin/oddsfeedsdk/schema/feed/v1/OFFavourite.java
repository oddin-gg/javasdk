package com.oddin.oddsfeedsdk.schema.feed.v1;

import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlType;

@XmlType(
        name = "favouriteType"
)
@XmlEnum(Integer.class)
public enum OFFavourite {
    @XmlEnumValue("1")
    YES(1);

    private final int value;

    OFFavourite(int v) {
        this.value = v;
    }

    public int value() {
        return this.value;
    }

    public static OFFavourite fromValue(int value) {
        OFFavourite[] values = values();
        for (OFFavourite item : values) {
            if (item.value == value) {
                return item;
            }
        }

        throw new IllegalArgumentException(String.valueOf(value));
    }
}
