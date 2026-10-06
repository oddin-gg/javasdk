package com.oddin.oddsfeedsdk.api.entities;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Where an event recovery is, by its request id, when it was asked: pending, then completed, failed
 * or timed out. Kept five minutes after it ends, then forgotten. New in 1.0.
 *
 * @param requestId the request id the recovery was started with
 * @param startedAt when it was asked for, by the SDK's clock
 * @param endedAt when it ended, by the SDK's clock; null while it is pending
 * @param reason why it failed or timed out; null otherwise
 */
public record EventRecoveryStatus(
        long requestId,
        long producerId,
        URN eventId,
        State state,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable String reason) {

    /** The states of an event recovery, as the Go SDK has them. */
    public enum State {
        /** Asked for; not every session that awaits its snapshot has seen it complete yet. */
        PENDING,
        /** Every session that awaits its snapshot has seen it complete. */
        COMPLETED,
        /**
         * The API did not accept it, its snapshot went with a lost queue, or the feed closed first.
         * A producer going down for another reason fails none, since the snapshot can still come.
         */
        FAILED,
        /** Its snapshot did not complete within the maximum recovery time. */
        TIMED_OUT
    }
}
