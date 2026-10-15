package com.oddin.oddsfeedsdk.internal.clock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A feed clock whose two sides a test moves apart: the wall clock steps, as an operator or an NTP
 * correction moves it, while the monotonic one stands, and the other way round. What measures a
 * duration between two local events must follow the monotonic side only.
 */
public final class SplitClock {

    private final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-10-15T12:00:00Z"));
    private final AtomicLong nanos = new AtomicLong();
    private final FeedClock clock = FeedClock.of(wall::get, nanos::get);

    public FeedClock clock() {
        return clock;
    }

    /** Steps the wall clock, back when negative; the monotonic clock stands. */
    public void stepWall(Duration by) {
        wall.updateAndGet(at -> at.plus(by));
    }

    /** Lets time pass on the monotonic clock; the wall clock stands. */
    public void advanceMono(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    /** Lets time pass as it really does: on both. */
    public void advance(Duration by) {
        stepWall(by);
        advanceMono(by);
    }
}
