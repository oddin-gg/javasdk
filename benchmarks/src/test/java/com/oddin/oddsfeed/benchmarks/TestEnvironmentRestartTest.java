package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Opens the SDK against the test environment and prints how a restart with live matches went, see
 * {@link TestEnvironmentRestart}. It runs only when {@code BENCHMARKS_TESTENV=test} (or the system
 * property {@code benchmarks.testenv}) is set, never where {@code CI} is, and takes the access token
 * from {@code ODDIN_FEED_TOKEN_TEST}; the token is handed to the SDK's configuration and goes nowhere
 * else. See the benchmarks README, "The test environment".
 */
@Execution(ExecutionMode.SAME_THREAD)
class TestEnvironmentRestartTest {

    private static final String TOKEN = "ODDIN_FEED_TOKEN_TEST";

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRestartWithLiveMatches() throws Exception {
        assumeTrue(System.getenv("CI") == null, "the test environment is not for CI");
        var settings = TestEnvironmentRestart.Settings.from(TestEnvironmentRestart.Settings::system);
        assumeTrue(settings.isPresent(), "BENCHMARKS_TESTENV=test names the test environment; it is not set");
        var token = System.getenv(TOKEN);
        assertThat(token).as(TOKEN).isNotBlank();

        var result = TestEnvironmentRestart.run(settings.get(), token);

        System.out.println(result.report());
        var dir = Path.of("target", "testenv");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve(
                        settings.get().label() + "-" + Instant.now().toString().replace(':', '-') + ".txt"),
                result.report());
    }
}
