package com.oddin.oddsfeedsdk.internal.clock;

import java.time.InstantSource;
import java.util.function.LongSupplier;

/**
 * The feed's clock: one is made for a feed and handed to everything that stamps a fact or measures
 * a duration, so the readings of a fact and of the machine that judges it come from one source.
 *
 * <p>The wall side is what the SDK publishes, and what it compares with a producer's own clock. The
 * monotonic side is for a duration between two local events, which a step of the wall clock must not
 * change. It is {@link System#nanoTime} read in millis from the moment the clock was made, plus one:
 * so it is at least 1 for good, as the recovery machine reads 0 as "never", whatever raw {@code
 * nanoTime} happens to start at, negative included. A duration is the difference of two readings.
 */
public final class FeedClock {

    private final InstantSource wall;
    private final LongSupplier monoMillis;

    private FeedClock(InstantSource wall, LongSupplier monoMillis) {
        this.wall = wall;
        this.monoMillis = monoMillis;
    }

    /** The system's wall clock and {@link System#nanoTime}. */
    public static FeedClock system() {
        return of(InstantSource.system(), System::nanoTime);
    }

    /**
     * A clock over a wall clock and a monotonic one.
     *
     * @param wall the wall clock
     * @param monoNanos a monotonic clock in nanos, as {@link System#nanoTime}: its origin is
     *     arbitrary, so it is read once now and the reading rebased to start at 1 millisecond
     */
    public static FeedClock of(InstantSource wall, LongSupplier monoNanos) {
        long origin = monoNanos.getAsLong();
        return new FeedClock(wall, () -> Math.floorDiv(monoNanos.getAsLong() - origin, 1_000_000L) + 1);
    }

    /**
     * For a test: a clock whose monotonic side is the millis of the given one, not rebased, so the
     * two sides move together and read the same number.
     */
    public static FeedClock following(InstantSource clock) {
        return new FeedClock(clock, clock::millis);
    }

    /** The wall clock, for what still reads an {@link InstantSource}. */
    public InstantSource wall() {
        return wall;
    }

    /** Epoch millis by the wall clock. */
    public long wallMillis() {
        return wall.millis();
    }

    /** Millis on the monotonic clock, at least 1; only the difference of two of them means anything. */
    public long monoMillis() {
        return monoMillis.getAsLong();
    }

    /** Both readings, one right after the other. */
    public Stamp stamp() {
        return new Stamp(wall.millis(), monoMillis.getAsLong());
    }
}
