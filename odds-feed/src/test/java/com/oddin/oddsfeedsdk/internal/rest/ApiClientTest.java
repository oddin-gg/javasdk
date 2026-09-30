package com.oddin.oddsfeedsdk.internal.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The REST client against the fake API: each rule of the call policy, and what it sends. */
class ApiClientTest {

    private static final URN MATCH = URN.parse("od:match:1");
    private static final String SUMMARY = "/v1/sports/en/sport_events/od:match:1/summary";
    private static final String WHOAMI = "/v1/users/whoami";
    private static final String OK_PRODUCERS = Fixtures.read("rest/producers/producers.xml");
    private static final String REFUSED = """
            <response response_code="FORBIDDEN"><action>whoami</action><message>token refused</message></response>""";

    private final FakeRestServer api = FakeRestServer.start();
    private final Recorded events = new Recorded();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
        api.close();
    }

    @Test
    void everyReadCallsThePathZeroZeroXCalled() {
        ApiClient client = client(b -> b);
        Locale en = Locale.ENGLISH;
        client.fetchWhoAmI();
        client.fetchProducers();
        client.fetchSports(en);
        client.fetchMatchStatusDescriptions(en);
        client.fetchMarketDescriptions(en);
        client.fetchMarketDescriptionsWithDynamicOutcomes(534, "od:dynamic_outcomes:27|v1", en);
        client.fetchMarketVoidReasons();
        client.fetchFixtureChanges(en);
        client.fetchFixture(MATCH, en);
        client.fetchSchedule(0, 100, en);
        client.fetchLiveMatches(en);
        client.fetchMatches(LocalDate.of(2026, 9, 30), en);
        client.fetchTournaments(URN.parse("od:sport:1"), en);
        client.fetchTournament(URN.parse("od:tournament:2"), en);
        client.fetchCompetitorProfile(URN.parse("od:competitor:3"), en);
        client.fetchPlayerProfile(URN.parse("od:player:4"), en);
        client.fetchMatchSummary(MATCH, en);
        client.fetchReplaySetContent();

        assertThat(api.requests())
                .extracting(r -> r.method() + " " + r.path() + (r.query() == null ? "" : "?" + r.query()))
                .containsExactly(
                        "GET /v1/users/whoami",
                        "GET /v1/descriptions/producers",
                        "GET /v1/sports/en/sports",
                        "GET /v1/descriptions/en/match_status",
                        "GET /v1/descriptions/en/markets",
                        "GET /v1/descriptions/en/markets/534/variants/od:dynamic_outcomes:27|v1",
                        "GET /v1/descriptions/void_reasons",
                        "GET /v1/sports/en/fixtures/changes",
                        "GET /v1/sports/en/sport_events/od:match:1/fixture",
                        "GET /v1/sports/en/schedules/pre/schedule?start=0&limit=100",
                        "GET /v1/sports/en/schedules/live/schedule",
                        "GET /v1/sports/en/schedules/2026-09-30/schedule",
                        "GET /v1/sports/en/sports/od:sport:1/tournaments",
                        "GET /v1/sports/en/tournaments/od:tournament:2/info",
                        "GET /v1/sports/en/competitors/od:competitor:3/profile",
                        "GET /v1/sports/en/players/od:player:4/profile",
                        "GET /v1/sports/en/sport_events/od:match:1/summary",
                        "GET /v1/replay?node_id=7");
    }

    @Test
    void everyControlCallSendsWhatZeroZeroXSent() {
        ApiClient client = client(b -> b);
        client.postEventOddsRecovery("pre", MATCH, 11);
        client.postEventStatefulRecovery("live", MATCH, 12);
        client.postRecovery("pre", 13, Instant.ofEpochMilli(1_777_000_000_000L));
        client.postRecovery("live", 14, null);
        client.putReplayEvent(MATCH);
        client.deleteReplayEvent(MATCH);
        client.postReplayStart(10, 500, true, false, "1");
        client.postReplayStart(null, null, null, null, null);
        client.postReplayStop();
        client.postReplayClear();

        assertThat(api.requests())
                .extracting(r -> r.method() + " " + r.path() + (r.query() == null ? "" : "?" + r.query()))
                .containsExactly(
                        "POST /v1/pre/odds/events/od:match:1/initiate_request?request_id=11&node_id=7",
                        "POST /v1/live/stateful_messages/events/od:match:1/initiate_request?request_id=12&node_id=7",
                        "POST /v1/pre/recovery/initiate_request?request_id=13&node_id=7&after=1777000000000",
                        "POST /v1/live/recovery/initiate_request?request_id=14&node_id=7",
                        "PUT /v1/replay/events/od:match:1?node_id=7",
                        "DELETE /v1/replay/events/od:match:1?node_id=7",
                        "POST /v1/replay/play?node_id=7&speed=10&max_delay=500&use_replay_timestamp=true"
                                + "&run_parallel=false&product=1",
                        "POST /v1/replay/play?node_id=7",
                        "POST /v1/replay/stop?node_id=7",
                        "POST /v1/replay/clear?node_id=7");
    }

    @Test
    void everyCallSaysWhoIsAskingAndWhichSdk() {
        client(b -> b).fetchWhoAmI();
        RecordedRequest request = api.requests().getFirst();
        assertThat(request.header("x-access-token")).isEqualTo("token");
        assertThat(request.header("accept")).isEqualTo("application/xml");
        assertThat(request.header("user-agent"))
                .isEqualTo(SdkVersion.userAgent())
                .startsWith("oddin-javasdk/");
        assertThat(request.header("x-oddin-sdk-version"))
                .isEqualTo(OddsFeed.getSdkVersion())
                .isNotBlank();
    }

    @Test
    void aRecoveryRequestGoesThroughWhileTheDataPoolIsExhausted() throws Exception {
        ApiClient client = client(b -> b.setRestConcurrencyLimit(2).setHttpClientTimeout(Duration.ofSeconds(10)));
        api.respond(
                SUMMARY,
                Reply.of(200, Fixtures.read("rest/match_summary/match_summary.xml"))
                        .after(Duration.ofSeconds(2)));
        try (ExecutorService loads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RAMatchSummaryEndpoint>> summaries = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                summaries.add(loads.submit(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH)));
            }
            api.awaitRequests("GET", SUMMARY, 2);

            long started = System.nanoTime();
            client.postRecovery("pre", 1, null);
            client.fetchMarketDescriptions(Locale.ENGLISH);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took)
                    .as("recovery and catalog calls wait for no entity load")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(api.requests("GET", SUMMARY))
                    .as("entity loads in flight")
                    .hasSize(2);
            for (Future<RAMatchSummaryEndpoint> summary : summaries) {
                assertThat(summary.get()).isNotNull();
            }
        }
        assertThat(api.requests("GET", SUMMARY)).hasSize(5);
        // the two loads, the recovery request and the descriptions
        assertThat(api.mostInFlight()).isLessThanOrEqualTo(4);
    }

    @Test
    void theDeadlineCoversTheWaitForAPermit() throws Exception {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(10)));
        api.respond(
                WHOAMI,
                Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml"))
                        .after(Duration.ofSeconds(2)));
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            // both recovery permits
            List<Future<RABookmakerDetail>> holders =
                    List.of(calls.submit(() -> client.fetchWhoAmI()), calls.submit(() -> client.fetchWhoAmI()));
            api.awaitRequests("GET", WHOAMI, 2);

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.fetchProducers(Deadline.in(Duration.ofMillis(300))))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not done within 300 ms, waiting for its turn");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            for (Future<RABookmakerDetail> holder : holders) {
                assertThat(holder.get()).isNotNull();
            }
        }
        assertThat(api.requests("GET", "/v1/descriptions/producers")).isEmpty();
    }

    @Test
    void theDeadlineCoversTheCallAndEveryRetry() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofMillis(800)));
        api.respond(SUMMARY, Reply.of(200, "").after(Duration.ofSeconds(2)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH))
                .isInstanceOf(ApiException.class)
                .hasMessageStartingWith("Failed to get data")
                .hasMessageContaining("waiting for the answer");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));

        api.respond(WHOAMI, 503, "");
        started = System.nanoTime();
        assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(api.requests("GET", WHOAMI))
                .as("retried inside the deadline")
                .hasSizeBetween(1, 2);
    }

    @Test
    void aBodyThatStallsMidwayIsCutOffAtTheDeadline() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofMillis(800)));
        api.respond(
                SUMMARY,
                Reply.of(200, Fixtures.read("rest/match_summary/match_summary.xml"))
                        .stallingMidBody(Duration.ofSeconds(3)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("waiting for the answer");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void aFailedConnectionIsRetriedForReadsAndRecoveryButNotForReplayControl() {
        ApiClient client = unreachable(b -> b);
        assertThatThrownBy(client::fetchWhoAmI).isInstanceOf(ApiException.class);
        assertThat(events.calls)
                .extracting(ApiCall::status, ApiCall::attempt)
                .containsExactly(tuple(0, 1), tuple(0, 2), tuple(0, 3));

        events.calls.clear();
        assertThatThrownBy(() -> client.postRecovery("pre", 1, null)).isInstanceOf(ApiException.class);
        assertThat(events.calls)
                .as("recovery requests are deduplicated by the API")
                .hasSize(3);

        events.calls.clear();
        assertThatThrownBy(() -> client.postReplayStart(null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageStartingWith("Failed to post data");
        assertThat(events.calls).as("replay control is not repeated").hasSize(1);
    }

    @Test
    void aPermitIsFreeWhileAFailedCallWaitsToTryAgain() throws Exception {
        ApiClient client = unreachable(b -> b.setRestConcurrencyLimit(1));
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> retrying = calls.submit(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH));
            long waited = System.nanoTime();
            while (events.calls.isEmpty()) {
                assertThat(Duration.ofNanos(System.nanoTime() - waited)).isLessThan(Duration.ofSeconds(5));
                Thread.sleep(5);
            }

            // the load above now waits at least 350 ms before its next attempt, without the permit
            long started = System.nanoTime();
            assertThatThrownBy(client::postReplayStop).isInstanceOf(ApiException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
            assertThatThrownBy(retrying::get).hasCauseInstanceOf(ApiException.class);
        }
    }

    @Test
    void anAnswerThatBreaksOffIsJudgedByItsStatus() {
        ApiClient client = client(b -> b);
        api.respond(WHOAMI, Reply.of(403, REFUSED).cutOffMidBody());
        assertThatThrownBy(client::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(HttpStatusException.refused(e)).isTrue());
        assertThat(events.refused).extracting(ApiCall::status).containsExactly(403);
        assertThat(api.requests("GET", WHOAMI)).hasSize(1);

        api.respond("/v1/replay/play", Reply.of(429, REFUSED).cutOffMidBody(), Reply.of(202, ""));
        client.postReplayStart(null, null, null, null, null);
        assertThat(api.requests("POST", "/v1/replay/play"))
                .as("a 429 is retried")
                .hasSize(2);
    }

    @Test
    void aSuccessOverTheSizeLimitFailsAtOnceAndAnErrorIsCutShort() {
        // padding after the root element: valid XML, and more than one network buffer
        String padded = Fixtures.read("rest/whoami/bookmaker_details.xml") + " ".repeat(100_000);
        int size = padded.getBytes(UTF_8).length;
        api.respond(WHOAMI, 200, padded);

        assertThat(limited(size, 50).fetchWhoAmI()).as("exactly at the limit").isNotNull();

        ApiClient tight = limited(size - 1, 50);
        assertThatThrownBy(tight::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("the answer is over " + (size - 1) + " bytes");
        assertThat(api.requests("GET", WHOAMI)).as("one byte over is final").hasSize(2);
        // both recovery permits came back
        for (int i = 0; i < 3; i++) {
            assertThat(tight.fetchProducers()).isNotNull();
        }

        api.respond(WHOAMI, 403, REFUSED + " ".repeat(1_000));
        assertThatThrownBy(tight::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(HttpStatusException.refused(e)).isTrue());
        assertThat(events.refused).extracting(ApiCall::status).containsExactly(403);
    }

    @Test
    void aBodyThatStallsIsStillJudgedByItsStatus() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofMillis(800)));
        api.respond(WHOAMI, Reply.of(403, REFUSED).stallingMidBody(Duration.ofSeconds(3)));
        assertThatThrownBy(client::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(HttpStatusException.refused(e)).isTrue());
        assertThat(events.refused).extracting(ApiCall::status).containsExactly(403);

        // replay control that the API answered 202 is done, however its body ends
        api.respond("/v1/replay/stop", Reply.of(202, "<accepted/>").stallingMidBody(Duration.ofSeconds(3)));
        long started = System.nanoTime();
        client.postReplayStop();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        assertThat(api.requests("POST", "/v1/replay/stop")).hasSize(1);
    }

    @Test
    void aSuccessWhoseBodyBreaksOffIsReadAgainUnlessItWasControl() {
        ApiClient client = client(b -> b);
        String summary = Fixtures.read("rest/match_summary/match_summary.xml");
        api.respond(SUMMARY, Reply.of(200, summary).cutOffMidBody(), Reply.of(200, summary));
        assertThat(client.fetchMatchSummary(MATCH, Locale.ENGLISH)).isNotNull();
        assertThat(api.requests("GET", SUMMARY)).hasSize(2);

        api.respond("/v1/replay/clear", Reply.of(202, "<accepted/>").cutOffMidBody());
        client.postReplayClear();
        assertThat(api.requests("POST", "/v1/replay/clear")).hasSize(1);
    }

    @Test
    void closingWakesACallWaitingToTryAgain() throws Exception {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(10)));
        api.respond(WHOAMI, Reply.of(429, "").withHeader("Retry-After", "5"));
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> waiting = calls.submit(() -> client.fetchWhoAmI());
            api.awaitRequest("GET", WHOAMI);
            Thread.sleep(100);
            long started = System.nanoTime();
            client.close();
            assertThatThrownBy(waiting::get).cause().hasMessageContaining("the feed is closed");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void closingEndsAStartupThatIsTryingAgain() throws Exception {
        ApiClient client = client(b -> b);
        api.startOutage(503);
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Startup> startup = calls.submit(() -> Startup.fetch(client, Duration.ofSeconds(30)));
            api.awaitRequest("GET", WHOAMI);
            long started = System.nanoTime();
            client.close();
            assertThatThrownBy(startup::get)
                    .cause()
                    .isInstanceOf(InitException.class)
                    .hasMessage("Failed to init odds feed: the feed was closed");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        }
        api.endOutage();
    }

    @Test
    void aClosedClientCallsNothing() {
        ApiClient client = client(b -> b);
        client.close();
        assertThatThrownBy(client::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("the feed is closed");
        assertThat(api.requests()).isEmpty();
    }

    @Test
    void anInterruptedCallSaysSo() {
        ApiClient client = client(b -> b);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(client::fetchWhoAmI)
                    .isInstanceOf(ApiException.class)
                    .hasMessageEndingWith(": interrupted");
        } finally {
            assertThat(Thread.interrupted()).as("the interrupt is kept").isTrue();
        }
    }

    @Test
    void aReadIsRetriedAfterAServerErrorAndControlIsNot() {
        ApiClient client = client(b -> b);
        api.respond(
                WHOAMI,
                Reply.of(503, ""),
                Reply.of(502, ""),
                Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml")));
        assertThat(client.fetchWhoAmI()).isNotNull();
        assertThat(api.requests("GET", WHOAMI)).hasSize(3);

        api.respond("/v1/replay/play", Reply.of(503, ""), Reply.of(202, ""));
        assertThatThrownBy(() -> client.postReplayStart(null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageStartingWith("Failed to post data");
        assertThat(api.requests("POST", "/v1/replay/play"))
                .as("replay control is not repeated")
                .hasSize(1);

        // the API deduplicates recovery requests on their request id
        api.respond("/v1/pre/recovery/initiate_request", Reply.of(500, ""), Reply.of(202, ""));
        client.postRecovery("pre", 5, null);
        assertThat(api.requests("POST", "/v1/pre/recovery/initiate_request")).hasSize(2);
    }

    @Test
    void aReadGivesUpAfterThreeAttempts() {
        ApiClient client = client(b -> b);
        api.respond(WHOAMI, 500, "");
        assertThatThrownBy(client::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("gave up after 3 attempts")
                .satisfies(e -> assertThat(HttpStatusException.statusOf(e)).isEqualTo(500));
        assertThat(api.requests("GET", WHOAMI)).hasSize(3);
    }

    @Test
    void tooManyRequestsIsRetriedAfterTheTimeTheApiAsksFor() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(5)));
        api.respond("/v1/replay/play", Reply.of(429, "").withHeader("Retry-After", "1"), Reply.of(202, ""));
        long started = System.nanoTime();
        client.postReplayStart(null, null, null, null, null);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(api.requests("POST", "/v1/replay/play"))
                .as("a 429 was not done, so even replay control is retried")
                .hasSize(2);
    }

    @Test
    void tooManyRequestsFailsAtOnceWhenTheApiAsksForLongerThanTheDeadline() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(2)));
        api.respond(WHOAMI, Reply.of(429, "").withHeader("Retry-After", "30"));
        long started = System.nanoTime();
        assertThatThrownBy(client::fetchWhoAmI)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("before it could try again")
                .satisfies(e -> assertThat(HttpStatusException.statusOf(e)).isEqualTo(429));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(api.requests("GET", WHOAMI)).hasSize(1);
    }

    @Test
    void aRefusedTokenFailsAtOnceAndIsReportedAsFatal() {
        ApiClient client = client(b -> b);
        for (int status : new int[] {401, 403}) {
            api.respond(WHOAMI, status, REFUSED);
            assertThatThrownBy(client::fetchWhoAmI)
                    .isInstanceOf(ApiException.class)
                    .hasMessage("token refused - whoami")
                    .satisfies(e -> assertThat(HttpStatusException.refused(e)).isTrue());
        }
        assertThat(api.requests("GET", WHOAMI)).as("never retried").hasSize(2);
        assertThat(events.refused).extracting(ApiCall::status).containsExactly(401, 403);
    }

    @Test
    void anApiErrorGivesTheExceptionItsMessageAsInZeroZeroX() {
        ApiClient client = client(b -> b);
        api.respond(SUMMARY, 404, Fixtures.read("rest/error/not_found.xml"));
        assertThatThrownBy(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH))
                .isInstanceOf(ApiException.class)
                .hasMessage("ERROR. Invalid market ID.. Not Found - Invalid market ID.. Not Found")
                .hasCauseInstanceOf(HttpStatusException.class);
        assertThat(api.requests("GET", SUMMARY)).as("a 4xx is final").hasSize(1);
    }

    @Test
    void anAnswerThatIsNotOkOrNotReadableFails() {
        ApiClient client = client(b -> b);
        api.respond(
                "/v1/descriptions/producers",
                200,
                OK_PRODUCERS.replace("response_code=\"OK\"", "response_code=\"FORBIDDEN\""));
        assertThatThrownBy(client::fetchProducers)
                .isInstanceOf(ApiException.class)
                .hasMessage("Not acceptable response code from API: FORBIDDEN");

        api.respond(SUMMARY, 200, "<match_summary");
        assertThatThrownBy(() -> client.fetchMatchSummary(MATCH, Locale.ENGLISH))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    void everyAttemptIsAnEventAndEveryDecodedAnswerIsHandedOn() {
        ApiClient client = client(b -> b);
        api.respond(
                "/v1/descriptions/en/markets",
                Reply.of(503, ""),
                Reply.of(200, Fixtures.read("rest/markets/market_descriptions.xml")));
        RAMarketDescriptions markets = client.fetchMarketDescriptions(Locale.ENGLISH);

        assertThat(events.calls)
                .extracting(ApiCall::method, ApiCall::status, ApiCall::attempt)
                .containsExactly(tuple("GET", 503, 1), tuple("GET", 200, 2));
        assertThat(events.calls.getFirst().failure()).isInstanceOf(HttpStatusException.class);
        assertThat(events.calls.get(1).failure()).isNull();
        assertThat(events.calls).allSatisfy(call -> {
            assertThat(call.uri().toString()).endsWith("/v1/descriptions/en/markets");
            assertThat(call.latency().isNegative()).isFalse();
        });
        assertThat(events.received).containsExactly(markets);
    }

    @Test
    void startupTriesAgainUntilTheApiAnswers() {
        ApiClient client = client(b -> b);
        api.respond(
                WHOAMI,
                Reply.of(503, ""),
                Reply.of(503, ""),
                Reply.of(503, ""),
                Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml")));
        Startup startup = Startup.fetch(client, Duration.ofSeconds(20));
        assertThat(startup.bookmaker()).isNotNull();
        assertThat(startup.producers().getProducer()).isNotEmpty();
        assertThat(api.requests("GET", WHOAMI)).hasSize(4);
    }

    @Test
    void startupFailsClearlyAtItsDeadline() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(1)));
        api.startOutage(503);
        long started = System.nanoTime();
        assertThatThrownBy(() -> Startup.fetch(client, Duration.ofMillis(2500)))
                .isInstanceOf(InitException.class)
                .hasMessageStartingWith(
                        "Failed to init odds feed: whoami and the producer list did not succeed within 2500 ms")
                .hasCauseInstanceOf(ApiException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        api.endOutage();
    }

    @Test
    void startupFailsAtOnceWhenTheTokenIsRefused() {
        ApiClient client = client(b -> b);
        api.respond(WHOAMI, 401, REFUSED);
        assertThatThrownBy(() -> Startup.fetch(client, Duration.ofSeconds(30)))
                .isInstanceOf(InitException.class)
                .hasMessage("Failed to init odds feed: the API refused the access token (401)");
        assertThat(api.requests("GET", WHOAMI)).hasSize(1);
    }

    @Test
    void startupFailsAtOnceWhenTheProducerListIsRefused() {
        ApiClient client = client(b -> b);
        api.respond("/v1/descriptions/producers", 403, REFUSED);
        assertThatThrownBy(() -> Startup.fetch(client, Duration.ofSeconds(30)))
                .isInstanceOf(InitException.class)
                .hasMessage("Failed to init odds feed: the API refused the access token (403)");
        assertThat(api.requests("GET", "/v1/descriptions/producers")).hasSize(1);
    }

    @Test
    void startupEndsAtItsTimeoutEvenWhenACallCouldTakeLonger() {
        ApiClient client = client(b -> b.setHttpClientTimeout(Duration.ofSeconds(10)));
        api.respond(
                WHOAMI,
                Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml"))
                        .after(Duration.ofSeconds(3)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> Startup.fetch(client, Duration.ofMillis(800)))
                .isInstanceOf(InitException.class)
                .hasMessageContaining("within 800 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anIdIsOnePathSegmentWhateverItHolds() {
        assertThat(ApiClient.segment("od:match:1")).isEqualTo("od:match:1");
        assertThat(ApiClient.segment("od:dynamic_outcomes:27|v1")).isEqualTo("od:dynamic_outcomes:27%7Cv1");
        assertThat(ApiClient.segment("a/b c")).isEqualTo("a%2Fb%20c");
        assertThat(ApiClient.queryValue("a&b=c")).isEqualTo("a%26b%3Dc");
    }

    @Test
    void theBackoffDoublesUpToFiveSecondsWithJitter() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            double expected = Math.min(500 * Math.pow(2, attempt - 1), 5000);
            assertThat(RestTransport.backoff(attempt).toMillis())
                    .as("attempt %d", attempt)
                    .isBetween((long) (expected * 0.7), (long) (expected * 1.3));
        }
    }

    /** A client whose bodies may be at most {@code maxBytes}, and an error's {@code maxErrorBytes}. */
    private ApiClient limited(int maxBytes, int maxErrorBytes) {
        OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .build();
        var client = new ApiClient(
                configuration,
                new RestTransport(configuration, events, TestTls.clientContext(), maxBytes, maxErrorBytes));
        open.add(client);
        return client;
    }

    /** A client whose API host has nobody listening: every connection fails. */
    private ApiClient unreachable(UnaryOperator<OddsFeedConfigurationBuilder> options) {
        String host;
        try (FakeRestServer gone = FakeRestServer.start()) {
            host = gone.apiHost();
        }
        OddsFeedConfiguration configuration = options.apply(OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment("mq.invalid", host)
                        .setAccessToken("token"))
                .build();
        var client = new ApiClient(configuration, events, TestTls.clientContext());
        open.add(client);
        return client;
    }

    private ApiClient client(UnaryOperator<OddsFeedConfigurationBuilder> options) {
        OddsFeedConfiguration configuration = options.apply(OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment("mq.invalid", api.apiHost())
                        .setAccessToken("token")
                        .setSDKNodeId(7))
                .build();
        var client = new ApiClient(configuration, events, TestTls.clientContext());
        open.add(client);
        return client;
    }

    private static final class Recorded implements ApiEvents {
        final List<ApiCall> calls = new CopyOnWriteArrayList<>();
        final List<ApiCall> refused = new CopyOnWriteArrayList<>();
        final List<Object> received = new CopyOnWriteArrayList<>();

        @Override
        public void called(ApiCall call) {
            calls.add(call);
        }

        @Override
        public void refused(ApiCall call) {
            refused.add(call);
        }

        @Override
        public void received(URI uri, Object decoded, byte[] body) {
            assertThat(body).isNotEmpty();
            received.add(decoded);
        }
    }
}
