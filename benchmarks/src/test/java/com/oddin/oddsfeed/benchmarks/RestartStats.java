package com.oddin.oddsfeed.benchmarks;

import java.util.Arrays;

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
        if (values.length == 0) {
            return 0;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percent / 100 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
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
}
