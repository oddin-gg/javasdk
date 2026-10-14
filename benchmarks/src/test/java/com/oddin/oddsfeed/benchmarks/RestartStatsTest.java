package com.oddin.oddsfeed.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.benchmarks.TestEnvironmentRestart.Reads;
import com.oddin.oddsfeed.benchmarks.TestEnvironmentRestart.Settings;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The test environment harness's own arithmetic and settings, which need no environment. */
class RestartStatsTest {

    @Test
    void aPercentileIsTheNearestRank() {
        var values = new long[] {50, 10, 40, 20, 30, 60, 90, 80, 70, 100};

        assertThat(RestartStats.percentile(values, 50)).isEqualTo(50);
        assertThat(RestartStats.percentile(values, 90)).isEqualTo(90);
        assertThat(RestartStats.percentile(values, 99)).isEqualTo(100);
        assertThat(RestartStats.percentile(values, 100)).isEqualTo(100);
        assertThat(RestartStats.percentile(new long[] {7}, 50)).isEqualTo(7);
        assertThat(RestartStats.percentile(new long[0], 99)).isZero();
        assertThat(values).as("the input is not sorted in place").startsWith(50, 10, 40);
    }

    @Test
    void aSessionIsCaughtUpFromItsLastLateMessage() {
        // handled at 1, 2, 3, 4, 5 s; the waits fall from 9 s to 1 s against a limit of 5 s
        var at = new long[] {1, 2, 3, 4, 5};
        var waits = new long[] {9, 8, 4, 6, 1};

        var result = RestartStats.waits(at, waits, 5);

        assertThat(result.messages()).isEqualTo(5);
        assertThat(result.late()).isEqualTo(3);
        assertThat(result.lastLateAt()).as("the 6 s wait of the fourth message").isEqualTo(4);
        assertThat(result.caughtUpAt()).isEqualTo(4);
        assertThat(result.maxWait()).isEqualTo(9);
    }

    @Test
    void aSessionNeverBehindIsCaughtUpWithItsFirstMessage() {
        var result = RestartStats.waits(new long[] {3, 4}, new long[] {1, 5}, 5);

        assertThat(result.late()).as("waiting exactly the limit is on time").isZero();
        assertThat(result.caughtUpAt()).isEqualTo(3);
    }

    @Test
    void aSessionWithNoMessageHasNoTime() {
        assertThat(RestartStats.waits(new long[0], new long[0], 5).caughtUpAt()).isEqualTo(-1);
    }

    @Test
    void anEndpointHasNoIdsNoQueryAndOneVariantPath() {
        assertThat(endpoint("GET", "/v1/sports/en/sport_events/od:match:123/summary?x=1"))
                .isEqualTo("GET /v1/sports/en/sport_events/{id}/summary");
        assertThat(endpoint("GET", "/v1/descriptions/en/markets/1/variants/od:player:7"))
                .isEqualTo("GET /v1/descriptions/en/markets/{id}/variants/{variant}");
        assertThat(endpoint("GET", "/v1/descriptions/en/markets/1/variants/sr%3Alist%3A12"))
                .isEqualTo("GET /v1/descriptions/en/markets/{id}/variants/{variant}");
    }

    private static String endpoint(String method, String path) {
        return TestEnvironmentRestart.endpoint(new ApiCallEvent(
                method, URI.create("https://host" + path), 200, Duration.ofMillis(5), 1, null, Instant.EPOCH));
    }

    @Test
    void withoutAnEnvironmentNamedNothingRuns() {
        assertThat(Settings.from(Map.<String, String>of()::get)).isEmpty();
        assertThat(Settings.from(Map.of(Settings.SELECTOR, " ")::get)).isEmpty();
    }

    @Test
    void onlyTheTestEnvironmentCanBeNamed() {
        assertThatThrownBy(() -> Settings.from(Map.of(Settings.SELECTOR, "production")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production");
    }

    @Test
    void theSettingsHaveDefaultsAndAreRead() {
        var defaults = Settings.from(Map.of(Settings.SELECTOR, "test")::get).orElseThrow();
        assertThat(defaults.reads()).isEqualTo(Reads.NOTHING);
        assertThat(defaults.eager()).isTrue();
        assertThat(defaults.sessions()).containsExactly(MessageInterest.ALL);
        assertThat(defaults.lagLimit()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.max()).isEqualTo(Duration.ofMinutes(12));
        assertThat(defaults.sustained())
                .as("a restart unless a duration is given")
                .isFalse();

        var set = Settings.from(Map.of(
                        Settings.SELECTOR,
                        "test",
                        Settings.SELECTOR + ".reads",
                        "names",
                        Settings.SELECTOR + ".eager",
                        "false",
                        Settings.SELECTOR + ".sessions",
                        "live_only, prematch_only",
                        Settings.SELECTOR + ".lag",
                        "10",
                        Settings.SELECTOR + ".label",
                        "names-3")::get)
                .orElseThrow();
        assertThat(set.reads()).isEqualTo(Reads.NAMES);
        assertThat(set.eager()).isFalse();
        assertThat(set.sessions()).isEqualTo(List.of(MessageInterest.LIVE_ONLY, MessageInterest.PREMATCH_ONLY));
        assertThat(set.lagLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(set.label()).isEqualTo("names-3");
    }

    private static final String TOKEN = "a token";

    private static TestEnvironmentRestart.Preflight decide(
            Map<String, String> lookup, @Nullable String ci, @Nullable String token) {
        return TestEnvironmentRestart.Preflight.decide(lookup::get, ci, token);
    }

    @Test
    void ciThatNamesTheTestEnvironmentIsRefusedBeforeAnythingOpens() {
        var decision = decide(Map.of(Settings.SELECTOR, "test"), "true", TOKEN);

        assertThat(decision).isInstanceOf(TestEnvironmentRestart.Preflight.Refuse.class);
        assertThat(((TestEnvironmentRestart.Preflight.Refuse) decision).reason())
                .contains("CI");
        // whatever else is set, and whether or not there is a token
        assertThat(decide(Map.of(Settings.SELECTOR, "test"), "1", null))
                .isInstanceOf(TestEnvironmentRestart.Preflight.Refuse.class);
        assertThat(decide(Map.of(Settings.SELECTOR, " test "), "", TOKEN))
                .isInstanceOf(TestEnvironmentRestart.Preflight.Refuse.class);
    }

    @Test
    void ciThatNamesNothingSkips() {
        assertThat(decide(Map.of(), "true", TOKEN)).isInstanceOf(TestEnvironmentRestart.Preflight.Skip.class);
        assertThat(decide(Map.of(Settings.SELECTOR, " "), "true", TOKEN))
                .isInstanceOf(TestEnvironmentRestart.Preflight.Skip.class);
    }

    @Test
    void withoutANameNothingRunsOutsideCiEither() {
        assertThat(decide(Map.of(), null, TOKEN)).isInstanceOf(TestEnvironmentRestart.Preflight.Skip.class);
        assertThat(decide(Map.of(), null, null)).isInstanceOf(TestEnvironmentRestart.Preflight.Skip.class);
    }

    @Test
    void aNameThatIsNotTheTestEnvironmentIsAnError() {
        assertThatThrownBy(() -> decide(Map.of(Settings.SELECTOR, "production"), null, TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTestEnvironmentNamedWithATokenOutsideCiRuns() {
        var decision = decide(Map.of(Settings.SELECTOR, "test", Settings.SELECTOR + ".reads", "names"), null, TOKEN);

        assertThat(decision).isInstanceOf(TestEnvironmentRestart.Preflight.Open.class);
        assertThat(((TestEnvironmentRestart.Preflight.Open) decision).settings().reads())
                .isEqualTo(Reads.NAMES);
    }

    @Test
    void theTestEnvironmentNamedWithoutATokenIsRefused() {
        assertThat(decide(Map.of(Settings.SELECTOR, "test"), null, null))
                .isInstanceOf(TestEnvironmentRestart.Preflight.Refuse.class);
        assertThat(decide(Map.of(Settings.SELECTOR, "test"), null, " "))
                .isInstanceOf(TestEnvironmentRestart.Preflight.Refuse.class);
    }

    @Test
    void lateIsMeasuredBeyondTheFloorAndNoFloorKnownCountsTheWholeAge() {
        assertThat(RestartStats.beyondFloor(9_000, 40)).isEqualTo(8_960);
        assertThat(RestartStats.beyondFloor(30, 40)).as("never negative").isZero();
        assertThat(RestartStats.beyondFloor(9_000, Long.MAX_VALUE))
                .as("before any live message has set the floor")
                .isEqualTo(9_000);
    }

    @Test
    void onlyAMessageMadeAfterTheRunBeganTeachesTheFloor() {
        long start = 1_000_000;

        assertThat(RestartStats.teachesFloor(start - 17_000, start))
                .as("a recovery's old message")
                .isFalse();
        assertThat(RestartStats.teachesFloor(start, start)).isTrue();
        assertThat(RestartStats.teachesFloor(start + 5, start)).isTrue();
    }

    @Test
    void aDurationMakesTheRunSustained() {
        var settings = Settings.from(Map.of(Settings.SELECTOR, "test", Settings.SELECTOR + ".duration", "60")::get)
                .orElseThrow();

        assertThat(settings.sustained()).isTrue();
        assertThat(settings.duration()).isEqualTo(Duration.ofMinutes(60));
    }
}
