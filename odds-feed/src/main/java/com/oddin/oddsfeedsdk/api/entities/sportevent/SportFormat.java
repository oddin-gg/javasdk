package com.oddin.oddsfeedsdk.api.entities.sportevent;

public enum SportFormat {
    CLASSIC("classic"),
    RACE("race"),
    UNKNOWN("unknown");

    private final String value;

    SportFormat(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
