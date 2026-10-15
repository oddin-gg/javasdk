package com.oddin.oddsfeed.benchmarks;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        return sorted[rank(percent, sorted.length) - 1];
    }

    /** The same for fractional values. */
    public static double percentile(double[] values, double percent) {
        if (values.length == 0) {
            return 0;
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[rank(percent, sorted.length) - 1];
    }

    /**
     * The nearest rank, from 1, of {@code percent} per cent of {@code length} values: {@code percent
     * / 100 * length} rounded up. It is worked out in decimal, as {@code percent} reads: with doubles,
     * 99.9 / 100 * 1000 is 999.0000000000001, which rounds up to the 1000th value.
     */
    static int rank(double percent, int length) {
        var rank = BigDecimal.valueOf(percent)
                .multiply(BigDecimal.valueOf(length))
                .divide(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.CEILING);
        return Math.max(rank.intValueExact(), 1);
    }

    /** A duration in nanoseconds as milliseconds, to a tenth below ten and whole above. */
    public static String ms(long nanos) {
        double ms = nanos / 1e6;
        return ms < 10 ? String.format(java.util.Locale.ROOT, "%.1f ms", ms) : Math.round(ms) + " ms";
    }
}
