package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The work the state machine posts out: requests for a REST worker, and channel resets for the
 * transport. Each runs elsewhere; a request's outcome comes back as a fact. Called on the actor's
 * thread, so an implementation hands the work over and returns.
 */
interface Outbox {

    void request(Call call);

    /**
     * Replaces the session's channel; what its queue holds is dropped. Once done, the transport's
     * side reports it as {@link RecoveryMachine#resetDone} with the same number.
     */
    void reset(int session, long number);

    /**
     * Completes a caller's future, elsewhere: what the caller chains to it must not run on the
     * actor's thread.
     */
    void reply(CompletableFuture<@Nullable Long> reply, @Nullable Long requestId);

    /** Fails a caller's future, elsewhere, as {@link #reply} completes it. */
    void fail(CompletableFuture<@Nullable Long> reply, RuntimeException failure);

    /** One recovery request. */
    sealed interface Call {
        long producerId();

        String producer();

        long requestId();

        /**
         * Everything since {@code after}, or a full snapshot when it is null.
         *
         * @param producer the producer's name, which the request's path takes
         */
        record Snapshot(
                long producerId,
                String producer,
                long requestId,
                @Nullable Instant after) implements Call {}

        /** One event's odds, or with {@code stateful} its stateful messages. */
        record Event(long producerId, String producer, long requestId, URN eventId, boolean stateful) implements Call {}
    }
}
