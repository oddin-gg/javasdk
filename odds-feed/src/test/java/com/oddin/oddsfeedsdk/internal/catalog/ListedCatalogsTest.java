package com.oddin.oddsfeedsdk.internal.catalog;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The void reasons and the match status descriptions over the real REST client, against the fake
 * API. Background refreshes wait in a queue until the test runs them.
 */
class ListedCatalogsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final String VOID_REASONS = "/v1/descriptions/void_reasons";
    private static final String STATUSES_EN = "/v1/descriptions/en/match_status";
    private static final String STATUSES_DE = "/v1/descriptions/de/match_status";

    private final FakeTime time = new FakeTime();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Runnable> queuedRefreshes = new ArrayList<>();
    private FakeRestServer api;
    private ApiClient client;
    private VoidReasons voidReasons;
    private MatchStatusDescriptions statuses;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(Duration.ofSeconds(10))
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        Duration timeout = Duration.ofSeconds(10);
        voidReasons = new VoidReasons(client, timeout, threads, queuedRefreshes::add, time, time);
        statuses = new MatchStatusDescriptions(client, timeout, threads, queuedRefreshes::add, time, time);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void theVoidReasonsAreListedByIdWithTheirParameters() {
        api.respond(
                VOID_REASONS,
                200,
                reasons("<void_reason id=\"4\" name=\"LATE_START\" description=\"Match started late\""
                        + " template=\"Late by {minutes} minutes\"><param name=\"minutes\"/></void_reason>"
                        + "<void_reason id=\"1\" name=\"NOT_PLAYED\" description=\"Match not played\"/>"));
        assertThat(voidReasons.all())
                .containsExactly(
                        new VoidReason(1, "NOT_PLAYED", "Match not played", null, List.of()),
                        new VoidReason(
                                4,
                                "LATE_START",
                                "Match started late",
                                "Late by {minutes} minutes",
                                List.of("minutes")));
        assertThat(requireNonNull(voidReasons.reason(4)).params()).containsExactly("minutes");
        assertThat(voidReasons.reason(99)).isNull();
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(1);
    }

    @Test
    void theVoidReasonsRefreshAfterTheirAgeAndAReloadFetchesAtOnce() {
        api.respond(VOID_REASONS, 200, reasons("<void_reason id=\"1\" name=\"NOT_PLAYED\" description=\"old\"/>"));
        voidReasons.all();
        api.respond(VOID_REASONS, 200, reasons("<void_reason id=\"1\" name=\"NOT_PLAYED\" description=\"new\"/>"));
        time.advance(VoidReasons.REFRESH_AGE.plusSeconds(1));
        assertThat(voidReasons.all().getFirst().description())
                .as("served stale")
                .isEqualTo("old");
        runRefreshes();
        assertThat(voidReasons.all().getFirst().description()).isEqualTo("new");

        api.respond(VOID_REASONS, 200, reasons("<void_reason id=\"1\" name=\"NOT_PLAYED\" description=\"newer\"/>"));
        assertThat(voidReasons.reload().getFirst().description()).isEqualTo("newer");
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(3);

        voidReasons.clear();
        voidReasons.all();
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(4);
        assertThat(voidReasons.health().name()).isEqualTo("void reasons");
        assertThat(voidReasons.health().servedStale()).isEqualTo(1);
    }

    @Test
    void theVoidReasonsPastTheirMaximumStalenessFailWhenTheyCannotBeFetched() {
        voidReasons.all();
        api.respond(VOID_REASONS, 404, Fixtures.read("rest/error/not_found.xml"));
        time.advance(VoidReasons.MAX_STALENESS.plusSeconds(1));
        assertThatThrownBy(voidReasons::all).isInstanceOf(ApiException.class);
    }

    @Test
    void theMatchStatusDescriptionsArePerLocale() {
        api.respond(STATUSES_EN, 200, statuses("<match_status id=\"0\" description=\"Not started\"/>"));
        api.respond(STATUSES_DE, 200, statuses("<match_status id=\"0\" description=\"Nicht begonnen\"/>"));
        assertThat(statuses.description(0, EN)).isEqualTo("Not started");
        assertThat(statuses.description(0, DE)).isEqualTo("Nicht begonnen");
        assertThat(statuses.description(7, EN)).isNull();
        statuses.description(0, EN);
        assertThat(api.requests("GET", STATUSES_EN)).hasSize(1);
        assertThat(api.requests("GET", STATUSES_DE)).hasSize(1);

        api.respond(STATUSES_EN, 200, statuses("<match_status id=\"0\" description=\"Not started yet\"/>"));
        time.advance(MatchStatusDescriptions.REFRESH_AGE.plusSeconds(1));
        assertThat(statuses.description(0, EN)).as("served stale").isEqualTo("Not started");
        runRefreshes();
        assertThat(statuses.description(0, EN)).isEqualTo("Not started yet");

        statuses.clear();
        statuses.description(0, DE);
        assertThat(api.requests("GET", STATUSES_DE)).hasSize(2);
        assertThat(statuses.health().name()).isEqualTo("match status descriptions");
    }

    @Test
    void aMatchStatusNewUpstreamShowsUpBeforeTheNextRefresh() {
        api.respond(STATUSES_EN, 200, statuses("<match_status id=\"0\" description=\"Not started\"/>"));
        statuses.description(0, EN);
        api.respond(
                STATUSES_EN,
                200,
                statuses("<match_status id=\"0\" description=\"Not started\"/>"
                        + "<match_status id=\"9\" description=\"Paused\"/>"));
        time.advance(Duration.ofMinutes(2));
        assertThat(statuses.description(9, EN)).as("the read does not wait").isNull();
        runRefreshes();
        assertThat(statuses.description(9, EN)).isEqualTo("Paused");
    }

    @Test
    void anEmptyListOfVoidReasonsDoesNotReplaceOneWithReasonsInIt() {
        api.respond(VOID_REASONS, 200, reasons(""));
        assertThat(voidReasons.all()).as("an empty list replaces nothing").isEmpty();
        api.respond(VOID_REASONS, 200, reasons("<void_reason id=\"1\" name=\"NOT_PLAYED\" description=\"old\"/>"));
        voidReasons.clear();
        voidReasons.all();
        api.respond(VOID_REASONS, 200, reasons(""));
        time.advance(VoidReasons.REFRESH_AGE.plusSeconds(1));
        voidReasons.all();
        runRefreshes();
        assertThat(voidReasons.all()).extracting(VoidReason::id).containsExactly(1);
        assertThat(voidReasons.health().failedFetches()).isEqualTo(1);
    }

    @Test
    void anEmptyListOfMatchStatusesDoesNotReplaceOneWithStatusesInIt() {
        api.respond(STATUSES_DE, 200, statuses(""));
        assertThat(statuses.description(0, DE))
                .as("an empty list replaces nothing")
                .isNull();
        api.respond(STATUSES_EN, 200, statuses("<match_status id=\"0\" description=\"Not started\"/>"));
        statuses.description(0, EN);
        api.respond(STATUSES_EN, 200, statuses(""));
        time.advance(MatchStatusDescriptions.REFRESH_AGE.plusSeconds(1));
        statuses.description(0, EN);
        runRefreshes();
        assertThat(statuses.description(0, EN)).isEqualTo("Not started");
        assertThat(statuses.health().failedFetches()).isEqualTo(1);
    }

    private void runRefreshes() {
        var queued = List.copyOf(queuedRefreshes);
        queuedRefreshes.clear();
        queued.forEach(Runnable::run);
    }

    private static String reasons(String reasons) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><void_reasons response_code=\"OK\">" + reasons
                + "</void_reasons>";
    }

    private static String statuses(String statuses) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><match_status_descriptions response_code=\"OK\">" + statuses
                + "</match_status_descriptions>";
    }
}
