package com.oddin.oddsfeedsdk.api.entities.sportevent;

public enum HomeAway {
    HOME(0), AWAY(1);

    private final int value;

    HomeAway(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
