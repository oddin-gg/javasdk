package com.oddin.oddsfeedsdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.OddsFeed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The builder keeps 0.0.x's hosts and defaults. */
class OddsFeedConfigurationBuilderTest {

    @Test
    void eachEnvironmentHasItsHostsInEachRegion() {
        assertThat(environment(builder().selectProduction()))
                .isEqualTo(new Environment("mq.oddin.gg", "api-mq.oddin.gg", 5672));
        assertThat(environment(builder().selectProduction(Region.AP_SOUTHEAST_1)))
                .isEqualTo(new Environment("mq.ap-southeast-1.oddin.gg", "api-mq.ap-southeast-1.oddin.gg", 5672));
        assertThat(environment(builder().selectIntegration()))
                .isEqualTo(new Environment("mq.integration.oddin.gg", "api-mq.integration.oddin.gg", 5672));
        assertThat(environment(builder().selectTest(Region.AP_SOUTHEAST_1)))
                .isEqualTo(new Environment(
                        "mq-test.integration.ap-southeast-1.oddin.dev",
                        "api-mq-test.integration.ap-southeast-1.oddin.dev",
                        5672));
        assertThat(environment(builder().selectEnvironment("mq.local", "api.local", 5671)))
                .isEqualTo(new Environment("mq.local", "api.local", 5671));
    }

    @Test
    void whatIsNotSetHasTheZeroZeroXDefault() {
        OddsFeedConfiguration configuration =
                builder().selectProduction().setAccessToken("token").build();
        assertThat(configuration.getAccessToken()).isEqualTo("token");
        assertThat(configuration.getDefaultLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(configuration.getMaxInactivitySeconds()).isEqualTo(20);
        assertThat(configuration.getMaxRecoveryExecutionMinutes()).isEqualTo(360);
        assertThat(configuration.getSdkNodeId()).isNull();
        assertThat(configuration.getExceptionHandlingStrategy()).isEqualTo(ExceptionHandlingStrategy.THROW);
        assertThat(configuration.getInitialSnapshotRecoveryInterval()).isNull();
        assertThat(configuration.getMaxMatchCacheSize()).isEqualTo(10_000L);
        assertThat(configuration.getMaxFixtureCacheSize()).isEqualTo(10_000L);
        assertThat(configuration.getMaxCompetitorCacheSize()).isEqualTo(20_000L);
        assertThat(configuration.getMaxPlayerCacheSize()).isEqualTo(50_000L);
    }

    @Test
    void whatIsSetIsWhatTheConfigurationHolds() {
        OddsFeedConfiguration configuration = builder()
                .selectIntegration()
                .setAccessToken("token")
                .setSDKNodeId(7)
                .setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH)
                .setInitialSnapshotRecoveryInterval(Duration.ofHours(2))
                .setMaxMatchCacheSize(1)
                .setMaxFixtureCacheSize(2)
                .setMaxCompetitorCacheSize(3)
                .setMaxPlayerCacheSize(4)
                .build();
        assertThat(configuration.getSdkNodeId()).isEqualTo(7);
        assertThat(configuration.getExceptionHandlingStrategy()).isEqualTo(ExceptionHandlingStrategy.CATCH);
        assertThat(configuration.getInitialSnapshotRecoveryInterval()).isEqualTo(Duration.ofHours(2));
        assertThat(configuration.getMaxMatchCacheSize()).isEqualTo(1);
        assertThat(configuration.getMaxFixtureCacheSize()).isEqualTo(2);
        assertThat(configuration.getMaxCompetitorCacheSize()).isEqualTo(3);
        assertThat(configuration.getMaxPlayerCacheSize()).isEqualTo(4);
    }

    @Test
    void theApiLimitsNewInOneZeroHaveTheirDefaults() {
        OddsFeedConfiguration configuration =
                builder().selectProduction().setAccessToken("token").build();
        assertThat(configuration.getHttpClientTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.getRestConcurrencyLimit()).isEqualTo(16);
        assertThat(configuration.getStartupTimeout()).isEqualTo(Duration.ofSeconds(90));

        OddsFeedConfiguration zeroZeroX = new OddsFeedConfiguration(
                "token",
                Locale.ENGLISH,
                20,
                360,
                null,
                ExceptionHandlingStrategy.THROW,
                new Environment("mq", "api", 5672),
                null,
                1,
                1,
                1,
                1);
        assertThat(zeroZeroX.getHttpClientTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(zeroZeroX.getStartupTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(zeroZeroX.getAmqpPrefetch()).isEqualTo(200);
        assertThat(zeroZeroX.getMaxMessageSize()).isEqualTo(1 << 20);
    }

    @Test
    void theStartupTimeoutFollowsTheHttpTimeoutUnlessItIsSet() {
        OddsFeedConfigurationBuilder builder = builder()
                .selectProduction()
                .setAccessToken("token")
                .setHttpClientTimeout(Duration.ofSeconds(5))
                .setRestConcurrencyLimit(4);
        assertThat(builder.build().getStartupTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(builder.build().getRestConcurrencyLimit()).isEqualTo(4);
        assertThat(builder.setStartupTimeout(Duration.ofMinutes(2)).build().getStartupTimeout())
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void thePrefetchAndTheMessageSizeHaveTheirDefaultsAndBounds() {
        OddsFeedConfiguration defaults =
                builder().selectProduction().setAccessToken("token").build();
        assertThat(defaults.getAmqpPrefetch()).isEqualTo(200);
        assertThat(defaults.getMaxMessageSize()).isEqualTo(1 << 20);
        OddsFeedConfiguration set = builder()
                .selectProduction()
                .setAccessToken("token")
                .setAmqpPrefetch(10_000)
                .setMaxMessageSize(4096)
                .build();
        assertThat(set.getAmqpPrefetch()).isEqualTo(10_000);
        assertThat(set.getMaxMessageSize()).isEqualTo(4096);
        assertThat(builder().setAmqpPrefetch(1)).isNotNull();
        assertThatThrownBy(() -> builder().setAmqpPrefetch(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 10000");
        assertThatThrownBy(() -> builder().setAmqpPrefetch(10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().setMaxMessageSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theApiLimitsMustBePositive() {
        assertThatThrownBy(() -> builder().setHttpClientTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP client timeout");
        assertThatThrownBy(() -> builder().setStartupTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startup timeout");
        assertThatThrownBy(() -> builder().setRestConcurrencyLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void theRecoveryAndFeedOptionsHaveTodaysNumbersUnlessSet() {
        OddsFeedConfiguration defaults =
                builder().selectProduction().setAccessToken("token").build();
        assertThat(defaults.getMaxInactivitySeconds()).isEqualTo(20);
        assertThat(defaults.getMaxRecoveryExecutionMinutes()).isEqualTo(360);
        assertThat(defaults.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getStaleMessageLimit()).isEqualTo(Duration.ofMinutes(2));
        assertThat(defaults.getStaleMessageWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.getExchangeName()).isEqualTo("oddinfeed");
        assertThat(defaults.getReplayExchangeName()).isEqualTo("oddinreplay");
        assertThat(defaults.isApiCallLogging()).isFalse();

        OddsFeedConfiguration zeroZeroX = new OddsFeedConfiguration(
                "token",
                Locale.ENGLISH,
                20,
                360,
                null,
                ExceptionHandlingStrategy.THROW,
                new Environment("mq", "api", 5672),
                null,
                1,
                1,
                1,
                1);
        assertThat(zeroZeroX.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(zeroZeroX.getStaleMessageLimit()).isEqualTo(Duration.ofMinutes(2));
        assertThat(zeroZeroX.getStaleMessageWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(zeroZeroX.getExchangeName()).isEqualTo("oddinfeed");
        assertThat(zeroZeroX.getReplayExchangeName()).isEqualTo("oddinreplay");
        assertThat(zeroZeroX.isApiCallLogging()).isFalse();
    }

    @Test
    void theRecoveryAndFeedOptionsAreCarriedToTheConfiguration() {
        OddsFeedConfiguration configuration = builder()
                .selectProduction()
                .setAccessToken("token")
                .setMaxInactivitySeconds(11)
                .setMaxRecoveryExecutionMinutes(360)
                .setMaxRecoveryExecutionMinutes(720)
                .setShutdownTimeout(Duration.ofMillis(1))
                .setStaleMessageLimit(Duration.ofSeconds(30))
                .setStaleMessageWindow(Duration.ofDays(1))
                .setExchangeName("feed.of.mine")
                .setReplayExchangeName("x".repeat(255))
                .setApiCallLogging(true)
                .build();
        assertThat(configuration.getMaxInactivitySeconds()).isEqualTo(11);
        assertThat(configuration.getMaxRecoveryExecutionMinutes()).isEqualTo(720);
        assertThat(configuration.getShutdownTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(configuration.getStaleMessageLimit()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.getStaleMessageWindow()).isEqualTo(Duration.ofDays(1));
        assertThat(configuration.getExchangeName()).isEqualTo("feed.of.mine");
        assertThat(configuration.getReplayExchangeName()).isEqualTo("x".repeat(255));
        assertThat(configuration.isApiCallLogging()).isTrue();
        assertThat(builder()
                        .selectProduction()
                        .setAccessToken("token")
                        .setShutdownTimeout(Duration.ofHours(1))
                        .build()
                        .getShutdownTimeout())
                .isEqualTo(Duration.ofHours(1));
    }

    /** 0.0.x had none of these setters; each refuses what the feed could not work with. */
    @Test
    void eachRecoveryAndFeedOptionRefusesWhatTheFeedCannotWorkWithAndKeepsItsValue() {
        var builder = builder().selectProduction().setAccessToken("token");
        record Refused(ThrowingCallable call, String message) {}
        var refused = List.of(
                new Refused(() -> builder.setMaxInactivitySeconds(10), "more than the 10 seconds"),
                new Refused(() -> builder.setMaxInactivitySeconds(-1), "more than the 10 seconds"),
                new Refused(() -> builder.setMaxRecoveryExecutionMinutes(359), "at least 360 minutes"),
                new Refused(() -> builder.setMaxRecoveryExecutionMinutes(0), "at least 360 minutes"),
                new Refused(() -> builder.setShutdownTimeout(Duration.ZERO), "shutdown timeout must be positive"),
                new Refused(() -> builder.setShutdownTimeout(Duration.ofSeconds(-5)), "must be positive"),
                new Refused(
                        () -> builder.setShutdownTimeout(Duration.ofHours(1).plusNanos(1)),
                        "shutdown timeout must be at most PT1H"),
                new Refused(() -> builder.setStaleMessageLimit(Duration.ZERO), "stale-message limit must be positive"),
                new Refused(
                        () -> builder.setStaleMessageLimit(Duration.ofDays(1).plusMillis(1)),
                        "stale-message limit must be at most PT24H"),
                new Refused(
                        () -> builder.setStaleMessageWindow(Duration.ofMillis(-1)),
                        "stale-message window must be positive"),
                new Refused(
                        () -> builder.setStaleMessageWindow(Duration.ofDays(2)),
                        "stale-message window must be at most PT24H"),
                new Refused(() -> builder.setExchangeName(""), "exchange name must not be blank"),
                new Refused(() -> builder.setExchangeName(" \t"), "exchange name must not be blank"),
                new Refused(() -> builder.setExchangeName("x".repeat(256)), "255 bytes in UTF-8"),
                // 128 characters of two bytes each
                new Refused(() -> builder.setReplayExchangeName("\u00e9".repeat(128)), "255 bytes in UTF-8"),
                new Refused(() -> builder.setReplayExchangeName(""), "replay exchange name must not be blank"));
        for (var call : refused) {
            assertThatThrownBy(call.call())
                    .as(call.message())
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(call.message());
        }

        OddsFeedConfiguration configuration = builder.build();
        assertThat(configuration.getMaxInactivitySeconds()).isEqualTo(20);
        assertThat(configuration.getMaxRecoveryExecutionMinutes()).isEqualTo(360);
        assertThat(configuration.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(configuration.getStaleMessageLimit()).isEqualTo(Duration.ofMinutes(2));
        assertThat(configuration.getStaleMessageWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(configuration.getExchangeName()).isEqualTo("oddinfeed");
        assertThat(configuration.getReplayExchangeName()).isEqualTo("oddinreplay");
    }

    @Test
    void anAccessTokenAndAnEnvironmentAreRequired() {
        assertThatThrownBy(() -> builder().selectProduction().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access token");
        assertThatThrownBy(() -> builder().setAccessToken("token").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment");
    }

    /** 0.0.x's Kotlin setters checked each argument, so a null threw at the call, not later. */
    @Test
    void aNullArgumentIsANullPointerExceptionAtOnceAndChangesNothing() {
        var builder = builder().selectProduction().setAccessToken("token");
        // each with the name of the argument its exception gives
        record Null(ThrowingCallable call, String name) {}
        var nulls = new ArrayList<Null>();
        nulls.add(new Null(() -> builder.selectProduction(nullValue()), "region"));
        nulls.add(new Null(() -> builder.selectIntegration(nullValue()), "region"));
        nulls.add(new Null(() -> builder.selectTest(nullValue()), "region"));
        nulls.add(new Null(() -> builder.selectEnvironment(nullValue(), "api.local"), "messagingHost"));
        nulls.add(new Null(() -> builder.selectEnvironment("mq.local", nullValue()), "apiHost"));
        nulls.add(new Null(() -> builder.selectEnvironment(nullValue(), "api.local", 5671), "messagingHost"));
        nulls.add(new Null(() -> builder.selectEnvironment("mq.local", nullValue(), 5671), "apiHost"));
        nulls.add(new Null(() -> builder.setAccessToken(nullValue()), "accessToken"));
        nulls.add(new Null(() -> builder.setExceptionHandlingStrategy(nullValue()), "exceptionHandlingStrategy"));
        nulls.add(new Null(() -> builder.setInitialSnapshotRecoveryInterval(nullValue()), "interval"));
        nulls.add(new Null(() -> builder.setHttpClientTimeout(nullValue()), "HTTP client timeout"));
        nulls.add(new Null(() -> builder.setStartupTimeout(nullValue()), "startup timeout"));
        nulls.add(new Null(() -> builder.setMessagingSslContext(nullValue()), "context"));
        nulls.add(new Null(() -> builder.setShutdownTimeout(nullValue()), "shutdown timeout"));
        nulls.add(new Null(() -> builder.setStaleMessageLimit(nullValue()), "stale-message limit"));
        nulls.add(new Null(() -> builder.setStaleMessageWindow(nullValue()), "stale-message window"));
        nulls.add(new Null(() -> builder.setExchangeName(nullValue()), "name"));
        nulls.add(new Null(() -> builder.setReplayExchangeName(nullValue()), "name"));
        nulls.add(new Null(() -> new Environment(nullValue(), "api.local", 5671), "messagingHost"));
        nulls.add(new Null(() -> new Environment("mq.local", nullValue(), 5671), "apiHost"));
        for (var argument : nulls) {
            assertThatThrownBy(argument.call())
                    .as(argument.name())
                    .isExactlyInstanceOf(NullPointerException.class)
                    .hasMessage(argument.name());
        }

        OddsFeedConfiguration configuration = builder.build();
        assertThat(configuration.getAccessToken()).isEqualTo("token");
        assertThat(configuration.getSelectedEnvironment())
                .isEqualTo(new Environment("mq.oddin.gg", "api-mq.oddin.gg", 5672));
        assertThat(configuration.getExceptionHandlingStrategy())
                .as("not CATCH by a null")
                .isEqualTo(ExceptionHandlingStrategy.THROW);
        assertThat(configuration.getInitialSnapshotRecoveryInterval()).isNull();
        assertThat(configuration.getHttpClientTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(configuration.getMessagingSslContext()).isNull();
    }

    @Test
    void theCompanionHandsOutTheSameBuilder() {
        assertThat(OddsFeed.Companion.getOddsFeedConfigurationBuilder())
                .isInstanceOf(OddsFeedConfigurationBuilder.class);
    }

    private static OddsFeedConfigurationBuilder builder() {
        return OddsFeed.getOddsFeedConfigurationBuilder();
    }

    /** A null where the API says there is none, as a careless caller passes it. */
    @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
    private static <T> T nullValue() {
        return null;
    }

    private static Environment environment(OddsFeedConfigurationBuilder builder) {
        return builder.setAccessToken("token").build().getSelectedEnvironment();
    }
}
