package com.oddin.oddsfeedsdk.internal.rest;

import java.time.Duration;

/**
 * The moment a piece of work must be done by. An API call has one, the HTTP client timeout from
 * when it is made, and waiting for a permit, every attempt and every pause between them all come
 * out of it.
 *
 * @param endNanos the moment, on the {@link System#nanoTime()} clock
 * @param budget how long it was when it was set, for messages
 */
public record Deadline(long endNanos, Duration budget) {

    /** A deadline {@code budget} from now. */
    public static Deadline in(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos(), budget);
    }

    /** What is left; zero once it has passed. */
    public Duration remaining() {
        long left = endNanos - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    public boolean passed() {
        return endNanos - System.nanoTime() <= 0;
    }

    /** Whichever of the two comes first. */
    public Deadline earlier(Deadline other) {
        return endNanos - other.endNanos <= 0 ? this : other;
    }
}
