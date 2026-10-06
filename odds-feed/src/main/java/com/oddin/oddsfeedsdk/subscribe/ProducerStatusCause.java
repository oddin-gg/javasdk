package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;

/**
 * Why a producer is down or up, more precisely than its {@link ProducerStatusReason}, which keeps
 * 0.0.x's values: several causes share one reason, and the cause tells them apart. New in 1.0.
 */
public enum ProducerStatusCause {
    /**
     * Down since the feed opened, not recovered yet: where every producer starts. No change is
     * reported with it.
     */
    STARTING,
    /** Up: the first recovery since the feed opened is complete. Reason {@code FIRST_RECOVERY_COMPLETED}. */
    FIRST_RECOVERY_COMPLETED,
    /** Up: a recovery after a down is complete. Reason {@code RETURNED_FROM_INACTIVITY}. */
    RECOVERY_COMPLETED,
    /**
     * Up: the sessions process the producer's messages on time again, after it was down for {@link
     * #PROCESSING_QUEUE_DELAY_VIOLATION}. Reason {@code RETURNED_FROM_INACTIVITY}, as in 0.0.x.
     */
    PROCESSING_QUEUE_DELAY_STABILIZED,
    /** Down: an alive said the feed is not subscribed to the producer. Reason {@code OTHER}. */
    UNSUBSCRIBED,
    /** Down: no alive from the producer for longer than the maximum inactivity. Reason {@code ALIVE_INTERVAL_VIOLATION}. */
    ALIVE_INTERVAL_VIOLATION,
    /**
     * Down: a session processes the producer's messages later than the maximum inactivity. Reason
     * {@code PROCESSING_QUEUE_DELAY_VIOLATION}.
     */
    PROCESSING_QUEUE_DELAY_VIOLATION,
    /** Down: the broker connection was lost, and what the sessions' queues held with it. Reason {@code OTHER}. */
    CONNECTION_LOST,
    /** Down: a session's channel was lost, and what its queue held with it. Reason {@code OTHER}. */
    CHANNEL_LOST,
    /** Down: a session opened, whose queue holds nothing from before. Reason {@code OTHER}. */
    SESSION_OPENED,
    /**
     * Down: the safety net replaced the queue of a session too far behind, dropping what it held.
     * Reason {@code PROCESSING_QUEUE_DELAY_VIOLATION}.
     */
    SAFETY_NET_RESET,
    /**
     * Down: recovery failed as many times in a row as the SDK tries; it tries again after a
     * cool-down, or at once when an alive comes after a gap. Reason {@code OTHER}.
     */
    RECOVERY_FAILED
}
