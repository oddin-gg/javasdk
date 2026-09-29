package com.oddin.oddsfeedsdk;

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
}
