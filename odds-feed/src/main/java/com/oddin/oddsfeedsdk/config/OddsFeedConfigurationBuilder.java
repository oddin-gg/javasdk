package com.oddin.oddsfeedsdk.config;

import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Builds a feed's configuration: an environment and an access token are required, the rest has defaults. */
public final class OddsFeedConfigurationBuilder {

    private static final int DEFAULT_MESSAGING_PORT = 5672;

    private @Nullable String accessToken;
    private final Locale defaultLocale = Locale.ENGLISH;
    private @Nullable Environment selectedEnvironment;
    private final int maxInactivitySeconds = 20;
    private final int maxRecoveryExecutionMinutes = 360;
    private @Nullable Integer sdkNodeId;
    private ExceptionHandlingStrategy exceptionHandlingStrategy = ExceptionHandlingStrategy.THROW;
    private @Nullable Duration initialSnapshotRecoveryInterval;
    private long maxMatchCacheSize = OddsFeedConfiguration.DEFAULT_MAX_MATCH_CACHE_SIZE;
    private long maxFixtureCacheSize = OddsFeedConfiguration.DEFAULT_MAX_FIXTURE_CACHE_SIZE;
    private long maxCompetitorCacheSize = OddsFeedConfiguration.DEFAULT_MAX_COMPETITOR_CACHE_SIZE;
    private long maxPlayerCacheSize = OddsFeedConfiguration.DEFAULT_MAX_PLAYER_CACHE_SIZE;

    /** Public because 0.0.x's constructor was, to Java callers; {@code OddsFeed.getOddsFeedConfigurationBuilder()} makes one. */
    public OddsFeedConfigurationBuilder() {}

    public OddsFeedConfigurationBuilder selectProduction() {
        return selectProduction(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectProduction(Region region) {
        return select("mq." + region.getHost() + "oddin.gg", "api-mq." + region.getHost() + "oddin.gg");
    }

    public OddsFeedConfigurationBuilder selectIntegration() {
        return selectIntegration(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectIntegration(Region region) {
        return select(
                "mq.integration." + region.getHost() + "oddin.gg",
                "api-mq.integration." + region.getHost() + "oddin.gg");
    }

    public OddsFeedConfigurationBuilder selectTest() {
        return selectTest(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectTest(Region region) {
        return select(
                "mq-test.integration." + region.getHost() + "oddin.dev",
                "api-mq-test.integration." + region.getHost() + "oddin.dev");
    }

    public OddsFeedConfigurationBuilder selectEnvironment(String messagingHost, String apiHost) {
        return select(messagingHost, apiHost);
    }

    public OddsFeedConfigurationBuilder selectEnvironment(String messagingHost, String apiHost, int messagingPort) {
        selectedEnvironment = new Environment(messagingHost, apiHost, messagingPort);
        return this;
    }

    private OddsFeedConfigurationBuilder select(String messagingHost, String apiHost) {
        return selectEnvironment(messagingHost, apiHost, DEFAULT_MESSAGING_PORT);
    }

    public OddsFeedConfigurationBuilder setAccessToken(String accessToken) {
        this.accessToken = accessToken;
        return this;
    }

    public OddsFeedConfigurationBuilder setSDKNodeId(int sdkNodeId) {
        this.sdkNodeId = sdkNodeId;
        return this;
    }

    public OddsFeedConfigurationBuilder setExceptionHandlingStrategy(
            ExceptionHandlingStrategy exceptionHandlingStrategy) {
        this.exceptionHandlingStrategy = exceptionHandlingStrategy;
        return this;
    }

    public OddsFeedConfigurationBuilder setInitialSnapshotRecoveryInterval(Duration interval) {
        this.initialSnapshotRecoveryInterval = interval;
        return this;
    }

    public OddsFeedConfigurationBuilder setMaxMatchCacheSize(long maxMatchCacheSize) {
        this.maxMatchCacheSize = maxMatchCacheSize;
        return this;
    }

    public OddsFeedConfigurationBuilder setMaxFixtureCacheSize(long maxFixtureCacheSize) {
        this.maxFixtureCacheSize = maxFixtureCacheSize;
        return this;
    }

    public OddsFeedConfigurationBuilder setMaxCompetitorCacheSize(long maxCompetitorCacheSize) {
        this.maxCompetitorCacheSize = maxCompetitorCacheSize;
        return this;
    }

    public OddsFeedConfigurationBuilder setMaxPlayerCacheSize(long maxPlayerCacheSize) {
        this.maxPlayerCacheSize = maxPlayerCacheSize;
        return this;
    }

    /**
     * The configuration.
     *
     * @throws IllegalArgumentException without an access token or an environment
     */
    public OddsFeedConfiguration build() {
        String token = accessToken;
        if (token == null) {
            throw new IllegalArgumentException("Missing access token. Please set access token.");
        }
        Environment environment = selectedEnvironment;
        if (environment == null) {
            throw new IllegalArgumentException("Missing environment. Please select environment.");
        }
        return new OddsFeedConfiguration(
                token,
                defaultLocale,
                maxInactivitySeconds,
                maxRecoveryExecutionMinutes,
                sdkNodeId,
                exceptionHandlingStrategy,
                environment,
                initialSnapshotRecoveryInterval,
                maxMatchCacheSize,
                maxFixtureCacheSize,
                maxCompetitorCacheSize,
                maxPlayerCacheSize);
    }
}
