package com.oddin.oddsfeedsdk.internal.events;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import org.jspecify.annotations.Nullable;

/**
 * A producer's status as the client hears it. Its timestamp has the time of the change in all four
 * places, as 0.0.x gave it.
 */
record ProducerStatusMessage(
        @Nullable Producer producer,
        MessageTimestamp timestamp,
        boolean down,
        boolean delayed,
        ProducerStatusReason reason)
        implements ProducerStatus {

    @Override
    public @Nullable Producer getProducer() {
        return producer;
    }

    @Override
    public MessageTimestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean isDown() {
        return down;
    }

    @Override
    public boolean isDelayed() {
        return delayed;
    }

    @Override
    public ProducerStatusReason getProducerStatusReason() {
        return reason;
    }
}
