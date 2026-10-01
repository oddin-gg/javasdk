package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.jspecify.annotations.Nullable;

/**
 * The refused logins since the last successful connection. Only refusals that have gone on for a
 * whole window, and at least a few of them, mean the refusal is for real: an auth backend being
 * deployed, a virtual host or its permissions being written, refuse for a while and then let the
 * same token in. Counted by the clock, not by attempts, which the backoff spaces closer at first.
 * Used under the transport's lock.
 */
final class Refusals {

    private final int enough;
    private final Duration window;
    private final InstantSource clock;
    private @Nullable Instant first;
    private int count;

    Refusals(int enough, Duration window, InstantSource clock) {
        this.enough = enough;
        this.window = window;
        this.clock = clock;
    }

    /** Counts one; true once there are enough and they have gone on for the window. */
    boolean refusedTooOften() {
        Instant now = clock.instant();
        Instant since = first;
        if (since == null) {
            since = now;
            first = now;
        }
        count++;
        return count >= enough && !Duration.between(since, now).minus(window).isNegative();
    }

    /** How many were counted since the last successful connection. */
    int count() {
        return count;
    }

    /** How long they have gone on. */
    Duration span() {
        Instant since = first;
        return since == null ? Duration.ZERO : Duration.between(since, clock.instant());
    }

    /** A connection succeeded: the count starts again. */
    void clear() {
        first = null;
        count = 0;
    }
}
