package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcLogTest {

    @TempDir
    Path dir;

    private static final String G1 = """
            [0.004s] Using G1
            [0.074s] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 43M->42M(256M) 19.701ms
            [0.170s] GC(3) Pause Young (Concurrent Start) (G1 Evacuation Pause) 184M->184M(256M) 12.017ms
            [0.170s] GC(4) Concurrent Mark Cycle
            [0.188s] GC(4) Pause Remark 237M->156M(256M) 1.026ms
            [0.193s] GC(4) Pause Cleanup 177M->177M(256M) 0.024ms
            [0.199s] GC(4) Concurrent Mark Cycle 29.100ms
            [1.5s] GC(9) Pause Full (System.gc()) 2G->512K(3G) 120.5ms
            """;

    @Test
    void pausesAreReadToTheMicrosecondAndConcurrentPhasesAreNot() throws IOException {
        var log = dir.resolve("gc.log");
        Files.writeString(log, G1);

        var pauses = GcLog.pauses(log, 0);

        assertThat(pauses).extracting(GcLog.Pause::kind).containsExactly("Young", "Young", "Remark", "Cleanup", "Full");
        assertThat(pauses.get(0).millis()).isEqualTo(19.701);
        assertThat(pauses.get(0).beforeBytes()).isEqualTo(43L << 20);
        assertThat(pauses.get(0).afterBytes()).isEqualTo(42L << 20);
        assertThat(pauses.get(3).millis()).isEqualTo(0.024);
        assertThat(pauses.get(4).beforeBytes()).isEqualTo(2L << 30);
        assertThat(pauses.get(4).afterBytes()).isEqualTo(512L << 10);
    }

    @Test
    void pausesBeforeTheRunAreLeftOut() throws IOException {
        var log = dir.resolve("gc.log");
        Files.writeString(log, G1);

        assertThat(GcLog.pauses(log, 0.18)).extracting(GcLog.Pause::kind).containsExactly("Remark", "Cleanup", "Full");
    }
}
