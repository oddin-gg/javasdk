package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class StatsTest {

    /** 1 to 1000, in an order that is not sorted. */
    private static long[] thousand() {
        return LongStream.rangeClosed(1, 1000)
                .map(value -> (value * 377) % 1000 + 1)
                .toArray();
    }

    @Test
    void thePercentileOfAThousandValuesIsTheValueAtTheExactRank() {
        var values = thousand();

        assertThat(values).doesNotHaveDuplicates().startsWith(378, 755);
        assertThat(Stats.percentile(values, 50)).isEqualTo(500);
        assertThat(Stats.percentile(values, 99)).isEqualTo(990);
        assertThat(Stats.percentile(values, 99.9))
                .as("the 999th, not the maximum")
                .isEqualTo(999);
        assertThat(Stats.percentile(values, 100)).isEqualTo(1000);
        assertThat(Stats.percentile(values, 0.1)).isEqualTo(1);
    }

    @Test
    void theDoubleOverloadIsTheSame() {
        var values = LongStream.of(thousand()).asDoubleStream().toArray();

        assertThat(Stats.percentile(values, 50)).isEqualTo(500.0);
        assertThat(Stats.percentile(values, 99)).isEqualTo(990.0);
        assertThat(Stats.percentile(values, 99.9)).isEqualTo(999.0);
        assertThat(Stats.percentile(values, 100)).isEqualTo(1000.0);
    }

    @Test
    void theRestartReportTakesTheSameRank() {
        var values = thousand();

        assertThat(RestartStats.percentile(values, 50)).isEqualTo(500);
        assertThat(RestartStats.percentile(values, 99)).isEqualTo(990);
        assertThat(RestartStats.percentile(values, 99.9)).isEqualTo(999);
        assertThat(RestartStats.percentile(values, 100)).isEqualTo(1000);
    }

    @Test
    void aRankBetweenTwoValuesRoundsUpAndNeverBelowTheFirst() {
        assertThat(Stats.rank(99.9, 1000)).isEqualTo(999);
        assertThat(Stats.rank(99.9, 1001)).isEqualTo(1000);
        assertThat(Stats.rank(50, 3)).isEqualTo(2);
        assertThat(Stats.rank(1, 10)).isEqualTo(1);
        assertThat(Stats.rank(0.0001, 10)).isEqualTo(1);
    }
}
