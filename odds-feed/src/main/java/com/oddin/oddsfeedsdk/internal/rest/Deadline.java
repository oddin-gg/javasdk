package com.oddin.oddsfeedsdk.internal.rest;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The moment a piece of work must be done by. An API call has one, the HTTP client timeout from
 * when it is made, and waiting for a permit, every attempt and every pause between them all come
 * out of it.
 *
 * @param endNanos the moment, on the clock {@code nanos} reads
 * @param budget how long it was when it was set, for messages
 * @param nanos the monotonic clock in nanos it counts by: {@link System#nanoTime()}, or the feed's
 *     clock for what the feed's loaders start, so every duration of a feed runs on one clock
 */
public record Deadline(long endNanos, Duration budget, LongSupplier nanos) {

    /** On {@link System#nanoTime()}. */
    public Deadline(long endNanos, Duration budget) {
        this(endNanos, budget, System::nanoTime);
    }

    /** A deadline {@code budget} from now, on {@link System#nanoTime()}. */
    public static Deadline in(Duration budget) {
        return in(budget, System::nanoTime);
    }

    /** A deadline {@code budget} from now, on the monotonic clock {@code nanos} reads. */
    public static Deadline in(Duration budget, LongSupplier nanos) {
        return new Deadline(nanos.getAsLong() + budget.toNanos(), budget, nanos);
    }

    /** What is left; zero once it has passed. */
    public Duration remaining() {
        long left = endNanos - nanos.getAsLong();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    public boolean passed() {
        return endNanos - nanos.getAsLong() <= 0;
    }

    /**
     * Whichever of the two comes first, by what is left of each: the two may count on different
     * clocks, whose readings must never be compared with each other.
     */
    public Deadline earlier(Deadline other) {
        return remaining().compareTo(other.remaining()) <= 0 ? this : other;
    }
}
