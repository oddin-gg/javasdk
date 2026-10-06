package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the safety net did about a session too far behind one of its producers. When a session's
 * live messages stay older than the limit for long enough, the SDK first asks for a recovery of each
 * producer the session receives, then, once the API has accepted them all, replaces the session's
 * queue, dropping what it held: one recovery snapshot instead of a long stale backlog. New in 1.0.
 *
 * @param producer the producer, as the producer manager has it; null for one its list does not have
 * @param age how old the session's live messages from the producer were; zero for {@link
 *     Kind#REQUEST_FAILED}
 * @param reason why the API did not accept the recovery, for {@link Kind#REQUEST_FAILED}; null for
 *     {@link Kind#RESET}
 * @param at when the SDK reported it, by its clock
 */
public record SafetyNetEvent(
        Kind kind,
        OddsFeedSession session,
        long producerId,
        @Nullable Producer producer,
        Duration age,
        @Nullable String reason,
        Instant at) {

    /** What the safety net did. */
    public enum Kind {
        /**
         * It replaced the session's queue: what the session had not processed is dropped, and the
         * recoveries of the producers it receives are asked for again. The producers are down until
         * they complete.
         */
        RESET,
        /**
         * The API did not accept a recovery it asked for before a reset, so it reset nothing; it
         * tries again after a backoff.
         */
        REQUEST_FAILED
    }
}
