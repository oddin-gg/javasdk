package com.oddin.oddsfeedsdk.mq.entities;

public enum ProducerUpReason {
    FIRST_RECOVERY_COMPLETED,
    PROCESSING_QUEUE_DELAY_STABILIZED,
    RETURNED_FROM_INACTIVITY;

    public ProducerStatusReason toProducerStatusReason() {
        return switch (this) {
            case FIRST_RECOVERY_COMPLETED -> ProducerStatusReason.FIRST_RECOVERY_COMPLETED;
            case PROCESSING_QUEUE_DELAY_STABILIZED -> ProducerStatusReason.PROCESSING_QUEUE_DELAY_STABILIZED;
            case RETURNED_FROM_INACTIVITY -> ProducerStatusReason.RETURNED_FROM_INACTIVITY;
        };
    }
}
