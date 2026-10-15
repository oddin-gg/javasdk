package com.oddin.oddsfeed.benchmarks;

/** The arithmetic of {@link TestEnvironmentRestart}'s report, apart from the SDK so that CI can check it. */
final class RestartStats {

    private RestartStats() {}

    /**
     * The nearest-rank percentile: the smallest value that at least {@code percent} per cent of the
     * values are at or under. Zero for no values.
     *
     * @param percent more than 0, up to 100
     */
    static long percentile(long[] values, double percent) {
        return Stats.percentile(values, percent);
    }

    /**
     * How a session's messages waited for its callbacks.
     *
     * @param messages the messages handled
     * @param late the messages that waited longer than the limit
     * @param firstAt when the first message was handled
     * @param lastLateAt when the last late message was handled; -1 when none was
     * @param maxWait the longest a message waited
     */
    record Waits(int messages, int late, long firstAt, long lastLateAt, long maxWait) {

        /**
         * When the session caught up: from this time on, no message waited longer than the limit. The
         * time the last late message was handled, or the first message's when none was late: it never fell
         * behind. -1 when it handled nothing.
         */
        long caughtUpAt() {
            return messages == 0 ? -1 : lastLateAt >= 0 ? lastLateAt : firstAt;
        }
    }

    /**
     * @param at when each message was handled, in nanoseconds from the same start, in handling order
     * @param waits how long each of them had waited, in nanoseconds
     * @param limit the wait that counts as being behind, in nanoseconds
     */
    static Waits waits(long[] at, long[] waits, long limit) {
        if (at.length != waits.length) {
            throw new IllegalArgumentException("one wait for each message");
        }
        long lastLate = -1;
        int late = 0;
        long max = 0;
        for (int i = 0; i < at.length; i++) {
            if (waits[i] > limit) {
                late++;
                lastLate = at[i];
            }
            max = Math.max(max, waits[i]);
        }
        return new Waits(at.length, late, at.length == 0 ? -1 : at[0], lastLate, max);
    }

    /**
     * How much older a message was than the least a message ever took, in the unit of both: the one
     * definition of late, for the run's live decision and for the report. No floor known yet
     * ({@link Long#MAX_VALUE}) is none: the age counts whole.
     */
    static long beyondFloor(long age, long floor) {
        return Math.max(0, age - (floor == Long.MAX_VALUE ? 0 : floor));
    }

    /**
     * Whether a message teaches the floor: one made after the run began. A recovery's old messages say
     * how old they are, not what the producer's clock and the network add, and would hold the floor too
     * high until a live message came.
     */
    static boolean teachesFloor(long madeMillis, long runStartMillis) {
        return madeMillis >= runStartMillis;
    }
}
