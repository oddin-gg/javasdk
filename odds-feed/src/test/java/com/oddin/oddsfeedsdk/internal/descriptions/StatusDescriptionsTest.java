package com.oddin.oddsfeedsdk.internal.descriptions;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.catalog.MatchStatusDescriptions;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The match status descriptions as a match's status gives them, over the real catalog and REST
 * client, against the fake API. One throws and one catches, over the same catalog.
 */
class StatusDescriptionsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final String STATUSES_EN = "/v1/descriptions/en/match_status";
    private static final String STATUSES_DE = "/v1/descriptions/de/match_status";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private FakeRestServer api;
    private ApiClient client;
    private StatusDescriptions throwing;
    private StatusDescriptions catching;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(TIMEOUT)
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        var catalog = new MatchStatusDescriptions(client, TIMEOUT, threads);
        throwing = new StatusDescriptions(catalog, ExceptionHandlingStrategy.THROW, threads);
        catching = new StatusDescriptions(catalog, ExceptionHandlingStrategy.CATCH, threads);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void aStatusReadsItsDescriptionAndLooksUpOtherLocalesWhenAsked() {
        api.respond(STATUSES_DE, 200, statuses("<match_status id=\"1\" description=\"Beendet\"/>"));
        LocalizedStaticData ended = requireNonNull(throwing.get(1, List.of(EN)));
        assertThat(ended.getId()).isEqualTo(1);
        assertThat(ended.getDescription()).isEqualTo("Ended");
        assertThat(ended.getDescription(EN)).isEqualTo("Ended");
        assertThat(api.requests("GET", STATUSES_DE))
                .as("not before it is asked for")
                .isEmpty();
        assertThat(ended.getDescription(DE)).isEqualTo("Beendet");
        assertThat(api.requests("GET", STATUSES_EN)).hasSize(1);
        assertThat(api.requests("GET", STATUSES_DE)).hasSize(1);
    }

    @Test
    void aStatusNoLocaleDescribesIsNullUnderEitherStrategy() {
        assertThat(throwing.get(99, List.of(EN))).isNull();
        assertThat(catching.get(99, List.of(EN))).isNull();
        api.respond(STATUSES_DE, 200, statuses("<match_status id=\"0\" description=\"Nicht begonnen\"/>"));
        assertThat(requireNonNull(throwing.get(1, List.of(EN))).getDescription(DE))
                .as("a locale whose list does not describe it")
                .isNull();
    }

    @Test
    void aStatusInSeveralLocalesLoadsThemInParallelAndIsTheFirstThatDescribesIt() {
        api.respond(
                STATUSES_EN,
                Reply.of(200, statuses("<match_status id=\"0\" description=\"Not started\"/>"))
                        .after(Duration.ofMillis(300)));
        api.respond(
                STATUSES_DE,
                Reply.of(200, statuses("<match_status id=\"1\" description=\"Beendet\"/>"))
                        .after(Duration.ofMillis(300)));
        LocalizedStaticData ended = requireNonNull(throwing.get(1, List.of(EN, DE)));
        assertThat(api.mostInFlight()).as("both lists at once").isEqualTo(2);
        assertThat(ended.getDescription()).isEqualTo("Beendet");
        assertThat(ended.getDescription(EN)).isNull();
        assertThat(requireNonNull(throwing.get(0, List.of(EN, DE))).getDescription())
                .isEqualTo("Not started");
        assertThat(api.requests("GET", STATUSES_EN)).hasSize(1);
        assertThat(api.requests("GET", STATUSES_DE)).hasSize(1);
    }

    @Test
    void aLocaleThatFailsFailsTheStatusUnderThrowAndMakesItNullUnderCatch() {
        api.respond(STATUSES_DE, 400, Fixtures.read("rest/error/not_found.xml"));
        assertThatThrownBy(() -> throwing.get(1, List.of(EN, DE))).isInstanceOf(ApiException.class);
        assertThat(catching.get(1, List.of(EN, DE)))
                .as("never a description from part of the locales")
                .isNull();

        LocalizedStaticData thrown = requireNonNull(throwing.get(1, List.of(EN)));
        LocalizedStaticData caught = requireNonNull(catching.get(1, List.of(EN)));
        assertThatThrownBy(() -> thrown.getDescription(DE)).isInstanceOf(ApiException.class);
        assertThat(caught.getDescription(DE)).isNull();
    }

    @Test
    void aStatusInNoLocaleIsRefused() {
        assertThatThrownBy(() -> throwing.get(1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private static String statuses(String... statuses) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><match_status_descriptions response_code=\"OK\">"
                + String.join("", statuses) + "</match_status_descriptions>";
    }
}
