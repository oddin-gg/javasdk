package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Where an event recovery is, by its request id, at one moment: what {@link
 * RecoveryActor#recoveryStatus} answers. Immutable; a change makes a new one.
 *
 * @param startedAt when the recovery was asked for, by the SDK's clock
 * @param endedAt when it reached its end state, by the SDK's clock; null while it is pending
 * @param reason why it failed or timed out, for the log and the client; null otherwise
 */
public record EventRecoveryStatus(
        long requestId,
        long producerId,
        URN eventId,
        State state,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable String reason) {

    /** The Go SDK's four states of an event recovery. */
    public enum State {
        /** Asked for, and its snapshot complete not seen by every session that awaits it yet. */
        PENDING,
        /** Every session that awaits it has seen its snapshot complete. */
        COMPLETED,
        /**
         * The API did not accept it, its snapshot complete went with a lost queue, or the feed
         * closed first.
         */
        FAILED,
        /** No snapshot complete within the maximum recovery time. */
        TIMED_OUT
    }

    static EventRecoveryStatus pending(long requestId, long producerId, URN eventId, Instant startedAt) {
        return new EventRecoveryStatus(requestId, producerId, eventId, State.PENDING, startedAt, null, null);
    }

    /** The same recovery, ended in {@code state}. */
    EventRecoveryStatus ended(State state, Instant at, @Nullable String why) {
        return new EventRecoveryStatus(requestId, producerId, eventId, state, startedAt, at, why);
    }
}
