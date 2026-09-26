package com.oddin.oddsfeedsdk.mq.entities;

public enum ProducerDownReason {
    ALIVE_INTERVAL_VIOLATION,
    PROCESSING_QUEUE_DELAY_VIOLATION,
    OTHER;

    public ProducerStatusReason toProducerStatusReason() {
        return switch (this) {
            case ALIVE_INTERVAL_VIOLATION -> ProducerStatusReason.ALIVE_INTERVAL_VIOLATION;
            case PROCESSING_QUEUE_DELAY_VIOLATION -> ProducerStatusReason.PROCESSING_QUEUE_DELAY_VIOLATION;
            case OTHER -> ProducerStatusReason.OTHER;
        };
    }
}
