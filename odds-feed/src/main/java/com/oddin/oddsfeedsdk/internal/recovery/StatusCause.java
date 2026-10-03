package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;

/**
 * Why a producer went down or came back, more precisely than the public reason, which keeps 0.0.x's
 * values: several causes share one reason, and the client tells them apart by the cause.
 */
public enum StatusCause {
    /** Every producer starts down, before its first recovery; never reported. */
    STARTING(true, ProducerStatusReason.OTHER, "not recovered since the feed opened"),
    /** The first recovery since the feed opened is complete. */
    FIRST_RECOVERY_COMPLETED(false, ProducerStatusReason.FIRST_RECOVERY_COMPLETED, "first recovery completed"),
    /** A recovery after a down is complete. */
    RECOVERY_COMPLETED(false, ProducerStatusReason.RETURNED_FROM_INACTIVITY, "recovery completed"),
    /** The sessions caught up with a producer that was down for being processed late. */
    DELAY_STABILIZED(false, ProducerStatusReason.RETURNED_FROM_INACTIVITY, "processing caught up"),
    /** An alive said the feed is no longer subscribed to the producer. */
    UNSUBSCRIBED(true, ProducerStatusReason.OTHER, "an alive said the producer is unsubscribed"),
    /** No alive from the producer for longer than the maximum inactivity. */
    ALIVE_INTERVAL_VIOLATION(true, ProducerStatusReason.ALIVE_INTERVAL_VIOLATION, "no alive for too long"),
    /** A session processes the producer's messages later than the maximum inactivity. */
    PROCESSING_DELAY(true, ProducerStatusReason.PROCESSING_QUEUE_DELAY_VIOLATION, "messages processed too late"),
    /** The broker connection was lost, and what its queues held with it. */
    CONNECTION_LOST(true, ProducerStatusReason.OTHER, "the broker connection was lost"),
    /** A session's channel was lost, and what its queue held with it. */
    CHANNEL_LOST(true, ProducerStatusReason.OTHER, "a session's channel was lost"),
    /** A session opened, whose queue has nothing from before. */
    SESSION_OPENED(true, ProducerStatusReason.OTHER, "a session opened"),
    /** The safety net replaced a session's channel that had fallen too far behind. */
    SAFETY_NET_RESET(true, ProducerStatusReason.PROCESSING_QUEUE_DELAY_VIOLATION, "a session too far behind was reset"),
    /** Recovery failed as many times in a row as the cap allows; it is tried again after the cool-down. */
    RECOVERY_FAILED(true, ProducerStatusReason.OTHER, "recovery failed too often in a row");

    private final boolean down;
    private final ProducerStatusReason reason;
    private final String description;

    StatusCause(boolean down, ProducerStatusReason reason, String description) {
        this.down = down;
        this.reason = reason;
        this.description = description;
    }

    /** Whether the producer is down for this cause. */
    public boolean down() {
        return down;
    }

    /** The public reason, as 0.0.x gave it. */
    public ProducerStatusReason reason() {
        return reason;
    }

    /** The cause in a few words, for the log and the client. */
    public String description() {
        return description;
    }
}
