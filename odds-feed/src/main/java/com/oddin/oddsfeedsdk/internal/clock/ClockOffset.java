package com.oddin.oddsfeedsdk.internal.clock;

/**
 * How far a producer's clock is from the SDK's wall clock, measured on one of its alives, with what
 * is needed to keep it right when the wall clock steps afterwards.
 *
 * @param offset the SDK's wall clock minus the producer's, when the alive was received
 * @param skew the SDK's wall clock minus its monotonic one, at the same reading
 */
public record ClockOffset(long offset, long skew) {

    /**
     * How far the difference between the feed clock's wall and monotonic readings has to move from
     * where an offset was measured for the wall clock to count as stepped: far above the jitter of
     * reading two clocks and the slew of a synchronised one, a tenth of the least maximum
     * inactivity there is, so a step that is not seen shows as an age of a second at most.
     */
    public static final long WALL_STEP = 1_000;

    /**
     * The offset an alive shows.
     *
     * @param generatedAt the alive's timestamp, epoch millis by the producer's clock
     * @param received when the SDK received it
     */
    public static ClockOffset measured(long generatedAt, Stamp received) {
        return new ClockOffset(received.wall() - generatedAt, received.wall() - received.mono());
    }

    /**
     * The offset as of a reading. A step of the wall clock since it was measured moves the difference
     * between the clock's two readings, wall less monotonic, which no step can move: so a reading
     * whose difference has moved by more than {@link #WALL_STEP} from the alive's says the wall clock
     * stepped, and the offset is moved by the step, the producer's clock not having moved with it.
     * Less than that is the jitter of reading two clocks, and a slew, and leaves it.
     */
    public long at(Stamp reading) {
        return corrected(offset, skew, reading);
    }

    /** {@link #at} for an offset and a skew kept apart. */
    public static long corrected(long offset, long skew, Stamp reading) {
        long step = reading.wall() - reading.mono() - skew;
        return Math.abs(step) > WALL_STEP ? offset + step : offset;
    }
}
