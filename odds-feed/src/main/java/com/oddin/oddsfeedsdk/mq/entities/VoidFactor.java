package com.oddin.oddsfeedsdk.mq.entities;

public enum VoidFactor {
    REFUND_HALF(0.50),
    REFUND_FULL(1.00);

    private final double value;

    VoidFactor(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }
}
