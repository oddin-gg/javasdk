package com.oddin.oddsfeedsdk.stress;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/** The seed, and that a failing stress test says which one it ran with. */
class SeedTest {

    @Test
    void aSettingFixesTheSeedAndNoneDrawsAFreshOne() {
        assertThat(Seed.from("42")).isEqualTo(42);
        assertThat(Seed.from(" -7 ")).isEqualTo(-7);
        assertThat(Seed.from(null)).isNotEqualTo(Seed.from(null));
    }

    @Test
    void derivedSeedsFollowTheMasterAndDifferByName() {
        assertThat(Seed.derive(5, "reader-1")).isEqualTo(Seed.derive(5, "reader-1"));
        assertThat(Seed.derive(5, "reader-1")).isNotEqualTo(Seed.derive(5, "reader-2"));
        assertThat(Seed.derive(5, "reader-1")).isNotEqualTo(Seed.derive(6, "reader-1"));
    }

    @Test
    void aForcedFailurePrintsTheSeedAndStaysAFailure() {
        var captured = new ByteArrayOutputStream();
        var report = new Seed.Report(new PrintStream(captured, true, UTF_8), 1234);
        var failure = new AssertionError("forced");

        assertThatThrownBy(() -> report.handleTestExecutionException(null, failure))
                .isSameAs(failure);
        assertThat(captured.toString(UTF_8)).contains("STRESS SEED=1234").contains("-Dstress.seed=1234");
    }
}
