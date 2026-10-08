package com.oddin.oddsfeedsdk.internal.log;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets a repeated failure reach the log at a small, steady rate, so a failure that recurs per message,
 * per call or per tick does not flood it. The count-based form logs the first failure, then one in a
 * thousand, and the caller says how many have failed so far; the time-based form logs the first, then
 * at most once per interval. Thread-safe.
 */
public final class Throttle {

    /** The count-based form logs one in this many failures after the first. */
    public static final long EVERY = 1_000;

    private final AtomicLong failures = new AtomicLong();
    private boolean logged;
    private long loggedAtNanos;

    /** Counts one more failure, and returns the count; pass it to {@link #due(long)}. */
    public long count() {
        return failures.incrementAndGet();
    }

    /** Whether the failure with this count is logged: the first, then every thousandth. */
    public static boolean due(long count) {
        return count == 1 || count % EVERY == 0;
    }

    /** Whether a failure at {@code nowNanos} is logged: the first, then at most once per {@code interval}. */
    public synchronized boolean dueEvery(long nowNanos, Duration interval) {
        if (logged && nowNanos - loggedAtNanos < interval.toNanos()) {
            return false;
        }
        logged = true;
        loggedAtNanos = nowNanos;
        return true;
    }
}
