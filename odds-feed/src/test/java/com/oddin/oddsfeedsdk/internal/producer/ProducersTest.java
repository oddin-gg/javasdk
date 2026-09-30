package com.oddin.oddsfeedsdk.internal.producer;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.rest.Startup;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The producer manager and whoami over what the API sends at startup, and 0.0.x's answers. */
class ProducersTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final String PRODUCERS_PATH = "/v1/descriptions/producers";

    private final FakeRestServer api = FakeRestServer.start();
    private final ApiClient client;

    ProducersTest() {
        OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setSDKNodeId(7)
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
    }

    @AfterEach
    void close() {
        client.close();
        api.close();
    }

    @Test
    void theProducersAreTheOnesTheApiListed() {
        Producers producers =
                new Producers(Startup.fetch(client, Duration.ofSeconds(10)).producers());

        assertThat(producers.getAvailableProducers()).containsOnlyKeys(1L, 2L);
        Producer pre = requireNonNull(producers.getAvailableProducers().get(1L));
        assertThat(pre.getName()).isEqualTo("pre");
        assertThat(pre.getDescription()).isEqualTo("Prematch feed");
        assertThat(pre.getApiUrl()).isEqualTo("https://api.example.invalid/v1/pre");
        assertThat(pre.isAvailable()).isTrue();
        assertThat(pre.isEnabled()).isTrue();
        assertThat(pre.getProducerScopes()).containsExactly(ProducerScope.PREMATCH);
        assertThat(pre.getStatefulRecoveryWindowInMinutes()).isEqualTo(4320);
        assertThat(producers.getProducer(2L)).isNotNull();
        assertThat(producers.getActiveProducers()).containsOnlyKeys(1L, 2L);
    }

    @Test
    void aProducerTheApiListsAsInactiveIsAvailableButNotActive() {
        api.respond(
                PRODUCERS_PATH,
                200,
                fixture().replace("active=\"true\" scope=\"live\"", "active=\"false\" scope=\"live\""));
        Producers producers = new Producers(client.fetchProducers());
        assertThat(producers.getAvailableProducers()).containsOnlyKeys(1L, 2L);
        assertThat(producers.getActiveProducers()).containsOnlyKeys(1L);
        assertThat(producers.isProducerEnabled(2L))
                .as("an inactive producer starts disabled")
                .isFalse();
    }

    @Test
    void aProducerInBothScopesHasBoth() {
        // 0.0.x did not split the list, and such a producer had no scope at all
        api.respond(PRODUCERS_PATH, 200, fixture().replace("scope=\"live\"", "scope=\"live|prematch\""));
        Producers producers = new Producers(client.fetchProducers());
        assertThat(requireNonNull(producers.getAvailableProducers().get(2L)).getProducerScopes())
                .containsExactlyInAnyOrder(ProducerScope.LIVE, ProducerScope.PREMATCH);
        assertThat(Producers.scopes("prematch|future")).containsExactly(ProducerScope.PREMATCH);
        assertThat(Producers.scopes(null)).isEmpty();
    }

    @Test
    void aProducerTheListDoesNotHaveIsUnknownNotMadeUp() {
        Producers producers = producers();
        assertThat(producers.getProducer(7L)).isNull();
        assertThat(producers.isKnown(7L)).isFalse();
        // the rest answers as 0.0.x did
        assertThat(producers.isProducerEnabled(7L)).isFalse();
        assertThat(producers.isProducerDown(7L)).isTrue();
        producers.setProducerState(7L, true);
        producers.setProducerRecoveryFromTimestamp(7L, 1);
        producers.setDown(7L, false);
        assertThat(producers.isProducerDown(7L)).isTrue();
        assertThat(producers.getAvailableProducers()).doesNotContainKey(7L);
    }

    @Test
    void everyProducerStartsDownAndAProducerHeldReadsTheFeedAsItIsNow() {
        Producers producers = producers();
        Producer held = producer(producers, 1L);
        assertThat(held.isFlaggedDown()).isTrue();
        assertThat(producers.isProducerDown(1L)).isTrue();

        producers.setDown(1L, false);
        producers.setLastMessageTimestamp(1L, NOW.toEpochMilli());
        producers.setLastProcessedMessageGenTimestamp(1L, NOW.minusSeconds(3).toEpochMilli());
        var recovery = new Recovery(0, NOW.toEpochMilli(), 42, 7, true);
        producers.setRecoveryInfo(1L, recovery);

        assertThat(held.isFlaggedDown()).isFalse();
        assertThat(held.getLastMessageTimestamp()).isEqualTo(NOW.toEpochMilli());
        assertThat(held.getLastProcessedMessageGenTimestamp())
                .isEqualTo(NOW.minusSeconds(3).toEpochMilli());
        assertThat(held.getProcessingQueDelay()).isEqualTo(3_000);
        assertThat(held.getRecoveryInfo()).isEqualTo(recovery);
        assertThat(requireNonNull(held.getRecoveryInfo()).getRequestId()).isEqualTo(42);
        assertThat(requireNonNull(held.getRecoveryInfo()).getNodeId()).isEqualTo(7);
        assertThat(producers.isProducerDown(1L)).isFalse();
    }

    @Test
    void whetherAProducerIsEnabledReadsAsItWasWhenTheClientGotIt() {
        // 0.0.x's ProducerImpl took the enabled flag when it was made
        Producers producers = producers();
        Producer before = producer(producers, 1L);
        producers.setProducerState(1L, false);
        assertThat(before.isEnabled()).isTrue();
        assertThat(producer(producers, 1L).isEnabled()).isFalse();
        assertThat(producers.isProducerEnabled(1L)).isFalse();
    }

    @Test
    void theRecoveryStartMustBeInsideTheStatefulRecoveryWindow() {
        Producers producers = producers();
        long insideTheWindow = NOW.minus(Duration.ofDays(3)).toEpochMilli();
        producers.setProducerRecoveryFromTimestamp(1L, insideTheWindow);
        assertThat(producer(producers, 1L).getTimestampForRecovery()).isEqualTo(Instant.ofEpochMilli(insideTheWindow));

        long tooOld = insideTheWindow - 1;
        assertThatThrownBy(() -> producers.setProducerRecoveryFromTimestamp(1L, tooOld))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Last received message timestamp can not be more than '4320' minutes ago, producerId:1"
                        + " timestamp:" + tooOld + " (max recovery = '4320' minutes ago)");

        // an alive's generation time takes over once there is one; 0 clears the start
        producers.setLastAliveReceivedGenTimestamp(1L, NOW.toEpochMilli());
        assertThat(producer(producers, 1L).getTimestampForRecovery()).isEqualTo(NOW);
        long saved = NOW.minus(Duration.ofHours(1)).toEpochMilli();
        producers.setProducerRecoveryFromTimestamp(2L, saved);
        assertThat(producer(producers, 2L).getTimestampForRecovery()).isEqualTo(Instant.ofEpochMilli(saved));
        producers.setProducerRecoveryFromTimestamp(2L, 0);
        assertThat(producer(producers, 2L).getTimestampForRecovery())
                .as("0: a full snapshot")
                .isNull();
    }

    @Test
    void aMessageTimestampIsPositive() {
        Producers producers = producers();
        assertThatThrownBy(() -> producers.setLastMessageTimestamp(1L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMapsAreNewEachTimeAsInZeroZeroX() {
        Producers producers = producers();
        var first = producers.getAvailableProducers();
        first.clear();
        assertThat(producers.getAvailableProducers()).hasSize(2);
    }

    @Test
    void whoamiSaysWhoTheTokenBelongsTo() {
        Bookmaker bookmaker = Bookmaker.from(client.fetchWhoAmI());
        assertThat(bookmaker.getBookmakerId()).isEqualTo(53);
        assertThat(bookmaker.getVirtualHost()).isEqualTo("/oddinfeed/53");
        // a time without a zone, read in the JVM's default zone as 0.0.x read it
        assertThat(bookmaker.getExpireAt())
                .isEqualTo(Date.from(LocalDateTime.parse("2099-12-31T23:59:59")
                        .atZone(ZoneId.systemDefault())
                        .toInstant()));
        assertThat(bookmaker.connectionName(7)).isEqualTo("of-sdk-53-7");
        Instant expiry = bookmaker.getExpireAt().toInstant();
        assertThat(bookmaker.expiresSoon(expiry.minus(Duration.ofDays(8)))).isFalse();
        assertThat(bookmaker.expiresSoon(expiry.minus(Duration.ofDays(6)))).isTrue();
        // the Date handed out is a copy
        bookmaker.getExpireAt().setTime(0);
        assertThat(bookmaker.getExpireAt().toInstant()).isEqualTo(expiry);
        assertThat(bookmaker.connectionName(null)).isEqualTo("of-sdk-53--1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"expire_at=\"2099-12-31T23:59:59\"", "bookmaker_id=\"53\"", "virtual_host=\"/oddinfeed/53\""})
    void whoamiWithoutWhatTheFeedNeedsFailsTheStartup(String attribute) {
        String whoami = Fixtures.read("rest/whoami/bookmaker_details.xml");
        assertThat(whoami).contains(" " + attribute);
        api.respond("/v1/users/whoami", 200, whoami.replace(" " + attribute, ""));
        assertThatThrownBy(() -> Bookmaker.from(client.fetchWhoAmI()))
                .isInstanceOf(InitException.class)
                .hasMessage("Failed to init odds feed: whoami answered without "
                        + attribute.substring(0, attribute.indexOf('=')));
    }

    private Producers producers() {
        RAProducers list = client.fetchProducers();
        return new Producers(list, InstantSource.fixed(NOW));
    }

    private static Producer producer(Producers producers, long id) {
        return requireNonNull(producers.getProducer(id), "producer " + id);
    }

    private static String fixture() {
        return Fixtures.read("rest/producers/producers.xml");
    }
}
