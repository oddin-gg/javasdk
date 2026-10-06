package com.oddin.oddsfeedsdk;

import com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import org.jspecify.annotations.Nullable;

/** Recovery of one event's messages, on request. */
public interface RecoveryManager {
    /** The request id, or null when the request was not accepted. */
    @Nullable
    Long initiateEventOddsMessagesRecovery(long producerId, URN eventId);

    /** The request id, or null when the request was not accepted. */
    @Nullable
    Long initiateEventStatefulMessagesRecovery(long producerId, URN eventId);

    /**
     * Where the event recovery with this request id is: pending, completed, failed or timed out. Null
     * for an id this feed never started an event recovery with, and for one that ended more than five
     * minutes ago. New in 1.0.
     */
    default @Nullable EventRecoveryStatus getEventRecoveryStatus(long requestId) {
        return null;
    }
}
