package com.oddin.oddsfeed.benchmarks;

import java.util.Arrays;

/** The arithmetic of the load measurements' reports. */
public final class Stats {

    private Stats() {}

    /**
     * The nearest-rank percentile: the smallest value that at least {@code percent} per cent of the
     * values are at or under. Zero for no values.
     *
     * @param percent more than 0, up to 100
     */
    public static long percentile(long[] values, double percent) {
        if (values.length == 0) {
            return 0;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percent / 100 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    /** The same for fractional values. */
    public static double percentile(double[] values, double percent) {
        if (values.length == 0) {
            return 0;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percent / 100 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    /** A duration in nanoseconds as milliseconds, to a tenth below ten and whole above. */
    public static String ms(long nanos) {
        double ms = nanos / 1e6;
        return ms < 10 ? String.format(java.util.Locale.ROOT, "%.1f ms", ms) : Math.round(ms) + " ms";
    }
}
