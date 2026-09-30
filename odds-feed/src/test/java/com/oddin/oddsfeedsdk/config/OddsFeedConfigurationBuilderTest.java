package com.oddin.oddsfeedsdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.OddsFeed;
import java.time.Duration;
import java.util.Locale;
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
    void anAccessTokenAndAnEnvironmentAreRequired() {
        assertThatThrownBy(() -> builder().selectProduction().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access token");
        assertThatThrownBy(() -> builder().setAccessToken("token").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment");
    }

    @Test
    void theCompanionHandsOutTheSameBuilder() {
        assertThat(OddsFeed.Companion.getOddsFeedConfigurationBuilder())
                .isInstanceOf(OddsFeedConfigurationBuilder.class);
    }

    private static OddsFeedConfigurationBuilder builder() {
        return OddsFeed.getOddsFeedConfigurationBuilder();
    }

    private static Environment environment(OddsFeedConfigurationBuilder builder) {
        return builder.setAccessToken("token").build().getSelectedEnvironment();
    }
}
