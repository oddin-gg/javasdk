package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeTelemetryTest {

    @Test
    void itSamplesAllocationAndThreadsAndSummarisesThem() throws InterruptedException {
        try (var telemetry = RuntimeTelemetry.start(Duration.ofMillis(20))) {
            var sink = new byte[1 << 20][];
            for (int i = 0; i < sink.length; i++) {
                sink[i] = new byte[1];
            }
            Thread.sleep(100);
            assertThat(sink).isNotEmpty();
            var pauses = List.of(
                    new GcLog.Pause(telemetry.uptimeAtStartSeconds() + 0.05, "Young", 100, 50, 2.5),
                    new GcLog.Pause(telemetry.uptimeAtStartSeconds() + 0.08, "Young", 100, 40, 7.5));

            var summary = telemetry.summary(pauses, 2);

            assertThat(telemetry.ticks().size()).isGreaterThanOrEqualTo(3);
            assertThat(telemetry.ticks().getLast().allocatedBytes())
                    .isGreaterThan(telemetry.ticks().getFirst().allocatedBytes() + (1 << 20));
            assertThat(summary).contains("GC pauses: 2, p50 2.50 ms, p99 7.50 ms, max 7.50 ms");
            assertThat(summary).contains("pauses by kind: {Young=2}");
            assertThat(telemetry.csv()).startsWith("seconds,allocated_bytes,");
        }
    }

    @Test
    void percentilesAreNearestRank() {
        assertThat(Stats.percentile(new double[] {1, 2, 3, 4}, 50)).isEqualTo(2);
        assertThat(Stats.percentile(new double[] {1, 2, 3, 4}, 99)).isEqualTo(4);
        assertThat(Stats.percentile(new double[0], 99)).isZero();
        assertThat(Stats.ms(1_500_000)).isEqualTo("1.5 ms");
        assertThat(Stats.ms(42_400_000)).isEqualTo("42 ms");
    }
}
