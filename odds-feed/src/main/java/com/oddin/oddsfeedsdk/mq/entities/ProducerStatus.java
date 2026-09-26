package com.oddin.oddsfeedsdk.mq.entities;

public interface ProducerStatus extends Message {
    boolean isDown();

    boolean isDelayed();

    ProducerStatusReason getProducerStatusReason();
}
