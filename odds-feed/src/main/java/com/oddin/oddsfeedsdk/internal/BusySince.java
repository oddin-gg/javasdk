package com.oddin.oddsfeedsdk.internal;

/**
 * When a part of the SDK began what it is busy with, for the watchdog: a {@link System#nanoTime}
 * reading, which a change of the wall clock does not move, so a clock set forward makes no callback
 * look long, nor one set back a callback short. Only the difference of two readings means anything.
 */
public final class BusySince {

    /** What a part reads as its busy-since while it is idle. */
    public static final long IDLE = 0;

    private BusySince() {}

    /** Now, as a busy-since: {@link System#nanoTime}, but never {@link #IDLE}. */
    public static long now() {
        return mark(System.nanoTime());
    }

    /** {@code nanos} as a busy-since: itself, but a nanosecond later when it is {@link #IDLE}. */
    public static long mark(long nanos) {
        return nanos == IDLE ? IDLE + 1 : nanos;
    }
}
