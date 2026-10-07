package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The refused logins since the last successful connection. Only refusals that have gone on for a
 * whole window, and at least a few of them, mean the refusal is for real: an auth backend being
 * deployed, a virtual host or its permissions being written, refuse for a while and then let the
 * same token in. Counted by the time, not by attempts, which the backoff spaces closer at first:
 * by {@link System#nanoTime}, so a wall clock set forward makes no refusal fatal early, nor one set
 * back postpones it. Used under the transport's lock.
 */
final class Refusals {

    private final int enough;
    private final long windowNanos;
    /** {@link System#nanoTime}, or a test's. */
    private final LongSupplier nanos;
    /** When the first was counted, by {@link #nanos}; meaningless while {@link #count} is 0. */
    private long first;

    private int count;

    Refusals(int enough, Duration window, LongSupplier nanos) {
        this.enough = enough;
        this.windowNanos = window.toNanos();
        this.nanos = nanos;
    }

    /** Counts one; true once there are enough and they have gone on for the window. */
    boolean refusedTooOften() {
        long now = nanos.getAsLong();
        if (count == 0) {
            first = now;
        }
        count++;
        // by difference only, as nanoTime is read
        return count >= enough && now - first >= windowNanos;
    }

    /** How many were counted since the last successful connection. */
    int count() {
        return count;
    }

    /** How long they have gone on. */
    Duration span() {
        return count == 0 ? Duration.ZERO : Duration.ofNanos(nanos.getAsLong() - first);
    }

    /** A connection succeeded: the count starts again. */
    void clear() {
        count = 0;
    }
}
