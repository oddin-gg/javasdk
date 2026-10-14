package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.oddin.oddsfeed.benchmarks.ColdStart.Reads;
import com.oddin.oddsfeed.benchmarks.ColdStart.Result;
import com.oddin.oddsfeed.benchmarks.ColdStart.Settings;
import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;

/**
 * A restart against the fake API, with the eager entity preload on: how long the feed takes to be
 * caught up with a burst of first odds changes for matches nothing is cached for, and how long a
 * first message takes on a fresh feed. The time is held to {@code cold-budgets.properties}; the
 * requests the feed makes are counted exactly, so a match that costs another request fails the build.
 *
 * <p>The fake feed is a RabbitMQ container, so this needs Docker. Where there is none the tests skip
 * with a message, unless the environment variable {@code CI} is set: a build there that cannot run
 * them fails. {@code -Dbenchmarks.cold.matches=} and {@code -Dbenchmarks.cold.runs=} change the burst
 * and the number of repeats the budget's median is taken over.
 */
@Timeout(600)
class ColdStartBudgetTest {

    /** Every request the feed's own work is answered after: the fixed latency of the assumed API. */
    static final Duration LATENCY = Duration.ofMillis(50);

    /** The matches of the burst in the tests that do not time it, which need only enough to overlap. */
    private static final int SMALL_BURST = 40;

    private static final int MATCHES = Integer.getInteger("benchmarks.cold.matches", 200);
    private static final int RUNS = Integer.getInteger("benchmarks.cold.runs", 3);

    /** The variant markets each odds change of the variant mode carries. */
    private static final int VARIANTS = Integer.getInteger("benchmarks.cold.variants", 5);

    /** The SDK's deadline for one request: the HTTP client timeout, 30 s unless set. */
    private static final Duration CALL_DEADLINE = Duration.ofSeconds(30);

    private static @Nullable FakeFeed broker;
    private static @Nullable List<Result> scenario;
    private static @Nullable List<Result> variantScenario;
    private static @Nullable List<Duration> firstMessages;

    @BeforeAll
    static void startBroker() {
        boolean docker = DockerClientFactory.instance().isDockerAvailable();
        if (System.getenv("CI") != null) {
            assertThat(docker)
                    .as("Docker, which the cold scenario's fake feed needs, and CI is set")
                    .isTrue();
        } else {
            assumeTrue(docker, "no Docker here: the cold scenario's fake feed is a RabbitMQ container");
        }
        broker = FakeFeed.start();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.close();
        }
    }

    private static Settings burst(int matches, boolean eager, Reads reads) {
        return new Settings(matches, LATENCY, eager, reads);
    }

    /** The scenario, run {@link #RUNS} times once for the tests that look at it. */
    private static synchronized List<Result> scenario() throws InterruptedException {
        if (scenario == null) {
            var runs = new ArrayList<Result>();
            for (int i = 0; i < RUNS; i++) {
                var result = ColdStart.run(requireNonNull(broker), burst(MATCHES, true, Reads.NAME));
                System.out.printf(
                        "cold scenario run %d: caught up %d ms, %d ms after the last publish, %d requests, %d at once%n",
                        i + 1,
                        result.caughtUp().toMillis(),
                        result.afterLastPublish().toMillis(),
                        result.total(),
                        result.mostInFlight());
                runs.add(result);
            }
            scenario = runs;
        }
        return requireNonNull(scenario);
    }

    /** The variant mode: the same restart, its odds changes carrying variant markets whose names the callbacks read. */
    private static Settings variantBurst(int matches) {
        return new Settings(matches, LATENCY, true, Reads.NAME_AND_MARKETS, VARIANTS);
    }

    /** The variant mode, run {@link #RUNS} times once for the tests that look at it. */
    private static synchronized List<Result> variantScenario() throws InterruptedException {
        if (variantScenario == null) {
            var runs = new ArrayList<Result>();
            for (int i = 0; i < RUNS; i++) {
                var result = ColdStart.run(requireNonNull(broker), variantBurst(MATCHES));
                System.out.printf(
                        "cold variant scenario run %d: caught up %d ms, %d ms after the last publish, %d requests"
                                + " (%d variants), %d at once, longest callback %d ms, longest name read %d ms,"
                                + " health %s, %d health events%n",
                        i + 1,
                        result.caughtUp().toMillis(),
                        result.afterLastPublish().toMillis(),
                        result.total(),
                        result.count(ColdStart.VARIANTS),
                        result.mostInFlight(),
                        result.longestCallback().toMillis(),
                        result.longestRead().toMillis(),
                        result.health(),
                        result.healthEvents().size());
                result.healthEvents().forEach(event -> System.out.println("  health event: " + event));
                runs.add(result);
            }
            variantScenario = runs;
        }
        return requireNonNull(variantScenario);
    }

    private static synchronized List<Duration> firstMessages() throws InterruptedException {
        if (firstMessages == null) {
            var runs = new ArrayList<Duration>();
            for (int i = 0; i < RUNS; i++) {
                runs.add(ColdStart.firstMessage(requireNonNull(broker), burst(1, true, Reads.NAME)));
                System.out.printf(
                        "cold first message run %d: %d ms%n", i + 1, runs.get(i).toMillis());
            }
            firstMessages = runs;
        }
        return requireNonNull(firstMessages);
    }

    /** The requests of a feed that opened with the preload locales, before any message. */
    private static Map<String, Integer> openingRequests() {
        return Map.of(
                "GET /v1/users/whoami", 1,
                "GET /v1/descriptions/producers", 1,
                "GET /v1/descriptions/void_reasons", 1,
                "GET /v1/descriptions/en/markets", 1,
                "GET /v1/descriptions/en/match_status", 1,
                "GET /v1/sports/en/sports", 1);
    }

    /**
     * Exactly the opening's requests and the loads the settings call for. Nothing is POSTed: the
     * feed starts no recovery against the fake while it opens and takes a burst of messages, and
     * a recovery or replay request per match would show here as an unexpected key.
     */
    private static Map<String, Integer> expected(Settings settings) {
        var expected = new java.util.TreeMap<>(openingRequests());
        if (settings.summaries() > 0) {
            expected.put(ColdStart.SUMMARIES, settings.summaries());
        }
        if (settings.profiles() > 0) {
            expected.put(ColdStart.PROFILES, settings.profiles());
        }
        if (settings.variants() > 0) {
            expected.put(ColdStart.VARIANTS, settings.variants());
        }
        return expected;
    }

    /** A feed that does not come up leaves nothing running: its fake API is closed, and the failure says why. */
    @Test
    void aFeedThatFailsToOpenIsClosedWithItsApi() throws IOException {
        var feed = requireNonNull(broker);
        var api = FakeRestServer.start();
        int port = Integer.parseInt(api.apiHost().substring(api.apiHost().indexOf(':') + 1));
        feed.removeExchange(FakeFeed.EXCHANGE);
        try {
            assertThatThrownBy(() -> new ColdStart.Running(
                            api,
                            burst(1, true, Reads.NOTHING),
                            new ColdStart.Listener(Reads.NOTHING, 1),
                            feed,
                            Duration.ofSeconds(1)))
                    .isInstanceOf(Throwable.class);
        } finally {
            feed.restoreExchange(FakeFeed.EXCHANGE);
        }
        assertThatThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close())
                .as("the fake API still listens")
                .isInstanceOf(IOException.class);
    }

    /**
     * Eager on: every cold match is loaded once, by the preload, and nobody reads it - the callbacks
     * do not touch the match. A summary and one profile per competitor, no fixture, no tournament or
     * sport of its own.
     */
    @Test
    void eagerOnLoadsEveryColdMatchOnce() throws InterruptedException {
        var settings = burst(SMALL_BURST, true, Reads.NOTHING);
        var result = ColdStart.run(requireNonNull(broker), settings);

        assertThat(result.requests()).isEqualTo(expected(settings));
        assertThat(result.repeated()).as("a path asked for twice").isEmpty();
    }

    /** Eager off: a match no callback reads is not asked for at all. */
    @Test
    void eagerOffMakesNoSummaryCallForUnreadMatches() throws InterruptedException {
        var settings = burst(SMALL_BURST, false, Reads.NOTHING);
        var result = ColdStart.run(requireNonNull(broker), settings);

        assertThat(result.count(ColdStart.SUMMARIES)).isZero();
        assertThat(result.count(ColdStart.PROFILES)).isZero();
        assertThat(result.requests()).isEqualTo(expected(settings));
    }

    /** The scenario of the budget makes exactly the requests it should; one more of any kind fails it. */
    @Test
    void theColdScenarioMakesTheExpectedRequests() throws InterruptedException {
        var settings = burst(MATCHES, true, Reads.NAME);
        for (var result : scenario()) {
            assertThat(result.requests()).isEqualTo(expected(settings));
            assertThat(result.repeated()).as("a path asked for twice").isEmpty();
            assertThat(result.count(ColdStart.SUMMARIES)).isEqualTo(MATCHES);
            assertThat(result.count(ColdStart.PROFILES)).isEqualTo(MATCHES * Catalogs.COMPETITORS_PER_MATCH);
        }
    }

    /**
     * The variant mode: every variant market's description is fetched once, from its own request, by
     * the callback that first reads its name, and the variants of no two matches are the same.
     */
    @Test
    void theVariantScenarioFetchesEveryVariantOnce() throws InterruptedException {
        var settings = variantBurst(MATCHES);
        for (var result : variantScenario()) {
            assertThat(result.requests()).isEqualTo(expected(settings));
            assertThat(result.repeated()).as("a path asked for twice").isEmpty();
            assertThat(result.count(ColdStart.VARIANTS)).isEqualTo(MATCHES * VARIANTS);
        }
    }

    /** A small variant burst, to the request: the variants are fetched as the callbacks read them, and no others. */
    @Test
    void aVariantMarketIsFetchedOnlyWhenItsNameIsRead() throws InterruptedException {
        // the same odds changes, the callbacks not reading the markets: no variant is asked for
        var unread =
                ColdStart.run(requireNonNull(broker), new Settings(SMALL_BURST, LATENCY, true, Reads.NAME, VARIANTS));
        assertThat(unread.count(ColdStart.VARIANTS)).isZero();

        var read = ColdStart.run(requireNonNull(broker), variantBurst(SMALL_BURST));
        assertThat(read.requests()).isEqualTo(expected(variantBurst(SMALL_BURST)));
        assertThat(read.count(ColdStart.VARIANTS)).isEqualTo(SMALL_BURST * VARIANTS);
    }

    /**
     * With the preload the summaries load at once, side by side on the side-load workers, instead of
     * one by one as each callback asks. Measured 0.69 to 0.72 of the time in 14 runs of 40 and 200
     * matches; the bound leaves room.
     */
    @Test
    void eagerOnIsFasterToCatchUpThanOff() throws InterruptedException {
        var on = ColdStart.run(requireNonNull(broker), burst(SMALL_BURST, true, Reads.NAME));
        var off = ColdStart.run(requireNonNull(broker), burst(SMALL_BURST, false, Reads.NAME));

        assertThat(on.caughtUp().toMillis())
                .as("eager on %s against off %s", on.caughtUp(), off.caughtUp())
                .isLessThan((long) (off.caughtUp().toMillis() * 0.85));
        assertThat(off.count(ColdStart.SUMMARIES)).isEqualTo(SMALL_BURST);
    }

    @Test
    void theColdScenarioCatchesUpWithinItsBudget() throws IOException, InterruptedException {
        var runs = scenario();
        var caughtUp = median(runs.stream().map(Result::caughtUp).toList());
        var budget = budget("cold.caughtUp.ms");

        // the latency was really injected: with at most F requests answered at once, N of them
        // cannot be done in under ceil(N / F) latencies, whatever the SDK does
        for (var result : runs) {
            long floor = Math.ceilDiv(result.total(), result.mostInFlight()) * LATENCY.toMillis();
            assertThat(result.caughtUp().toMillis())
                    .as("%d requests, at most %d at once, %d ms each", result.total(), result.mostInFlight(), 50)
                    .isGreaterThanOrEqualTo(floor);
        }
        assertThat(caughtUp.toMillis())
                .as(
                        "the median of %d runs: %s",
                        runs.size(), runs.stream().map(Result::caughtUp).toList())
                .isLessThanOrEqualTo(budget);
    }

    /**
     * The variant mode against its budget: the callbacks read the variants one by one on the session's
     * thread, through the catalog's two permits. No read came near the call deadline, and the feed
     * stayed healthy, which the budget's own headroom does not say.
     */
    @Test
    void theVariantScenarioCatchesUpWithinItsBudget() throws IOException, InterruptedException {
        var runs = variantScenario();
        var caughtUp = median(runs.stream().map(Result::caughtUp).toList());
        var budget = budget("cold.variants.caughtUp.ms");

        for (var result : runs) {
            long floor = Math.ceilDiv(result.total(), result.mostInFlight()) * LATENCY.toMillis();
            assertThat(result.caughtUp().toMillis())
                    .as("%d requests, at most %d at once, %d ms each", result.total(), result.mostInFlight(), 50)
                    .isGreaterThanOrEqualTo(floor);
            assertThat(result.longestRead())
                    .as("the longest market name read, against the %s call deadline", CALL_DEADLINE)
                    .isLessThan(CALL_DEADLINE.dividedBy(2));
            assertThat(result.health())
                    .as("health events %s", result.healthEvents())
                    .isEqualTo(HealthState.HEALTHY);
        }
        assertThat(caughtUp.toMillis())
                .as(
                        "the median of %d runs: %s",
                        runs.size(), runs.stream().map(Result::caughtUp).toList())
                .isLessThanOrEqualTo(budget);
    }

    @Test
    void aFirstMessageIsReadWithinItsBudget() throws IOException, InterruptedException {
        var runs = firstMessages();
        var median = median(runs);

        assertThat(median.toMillis())
                .as("the median of %d runs: %s", runs.size(), runs)
                .isGreaterThanOrEqualTo(LATENCY.toMillis()) // the summary, at least
                .isLessThanOrEqualTo(budget("cold.firstMessage.ms"));
    }

    private static Duration median(List<Duration> durations) {
        var sorted = durations.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static long budget(String key) throws IOException {
        var budgets = new Properties();
        try (InputStream in =
                requireNonNull(ColdStartBudgetTest.class.getResourceAsStream("/cold-budgets.properties"))) {
            budgets.load(in);
        }
        return Long.parseLong(requireNonNull(budgets.getProperty(key), key));
    }
}
