package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;

/**
 * A producer went down or came back, or is down for another cause than before: what the events
 * dispatcher makes a producer status message of.
 *
 * @param delayed whether a session processes the producer's messages later than the maximum
 *     inactivity
 * @param timestamp when it changed, epoch millis by the SDK's clock
 */
public record ProducerStatusChange(long producerId, boolean down, boolean delayed, StatusCause cause, long timestamp) {

    /** The public reason, as 0.0.x gave it. */
    public ProducerStatusReason reason() {
        return cause.reason();
    }
}
