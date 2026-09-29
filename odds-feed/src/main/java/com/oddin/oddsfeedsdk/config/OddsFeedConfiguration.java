package com.oddin.oddsfeedsdk.config;

import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** A feed's configuration; {@link OddsFeedConfigurationBuilder} makes one. */
public final class OddsFeedConfiguration {

    public static final long DEFAULT_MAX_MATCH_CACHE_SIZE = 10_000L;
    public static final long DEFAULT_MAX_FIXTURE_CACHE_SIZE = 10_000L;
    public static final long DEFAULT_MAX_COMPETITOR_CACHE_SIZE = 20_000L;
    public static final long DEFAULT_MAX_PLAYER_CACHE_SIZE = 50_000L;

    /** 0.0.x was Kotlin, and its constants lived on the companion object too. */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    private final String accessToken;
    private final Locale defaultLocale;
    private final int maxInactivitySeconds;
    private final int maxRecoveryExecutionMinutes;
    private final @Nullable Integer sdkNodeId;
    private final ExceptionHandlingStrategy exceptionHandlingStrategy;
    private final Environment selectedEnvironment;
    private final @Nullable Duration initialSnapshotRecoveryInterval;
    private final long maxMatchCacheSize;
    private final long maxFixtureCacheSize;
    private final long maxCompetitorCacheSize;
    private final long maxPlayerCacheSize;

    /**
     * Public because 0.0.x's constructor was, to Java callers; {@link OddsFeedConfigurationBuilder}
     * is the way to make one.
     */
    public OddsFeedConfiguration(
            String accessToken,
            Locale defaultLocale,
            int maxInactivitySeconds,
            int maxRecoveryExecutionMinutes,
            @Nullable Integer sdkNodeId,
            ExceptionHandlingStrategy exceptionHandlingStrategy,
            Environment selectedEnvironment,
            @Nullable Duration initialSnapshotRecoveryInterval,
            long maxMatchCacheSize,
            long maxFixtureCacheSize,
            long maxCompetitorCacheSize,
            long maxPlayerCacheSize) {
        this.accessToken = accessToken;
        this.defaultLocale = defaultLocale;
        this.maxInactivitySeconds = maxInactivitySeconds;
        this.maxRecoveryExecutionMinutes = maxRecoveryExecutionMinutes;
        this.sdkNodeId = sdkNodeId;
        this.exceptionHandlingStrategy = exceptionHandlingStrategy;
        this.selectedEnvironment = selectedEnvironment;
        this.initialSnapshotRecoveryInterval = initialSnapshotRecoveryInterval;
        this.maxMatchCacheSize = maxMatchCacheSize;
        this.maxFixtureCacheSize = maxFixtureCacheSize;
        this.maxCompetitorCacheSize = maxCompetitorCacheSize;
        this.maxPlayerCacheSize = maxPlayerCacheSize;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    public int getMaxInactivitySeconds() {
        return maxInactivitySeconds;
    }

    public int getMaxRecoveryExecutionMinutes() {
        return maxRecoveryExecutionMinutes;
    }

    public @Nullable Integer getSdkNodeId() {
        return sdkNodeId;
    }

    public ExceptionHandlingStrategy getExceptionHandlingStrategy() {
        return exceptionHandlingStrategy;
    }

    public Environment getSelectedEnvironment() {
        return selectedEnvironment;
    }

    public @Nullable Duration getInitialSnapshotRecoveryInterval() {
        return initialSnapshotRecoveryInterval;
    }

    /** Most matches kept in memory; the oldest used is dropped and fetched again when read. */
    public long getMaxMatchCacheSize() {
        return maxMatchCacheSize;
    }

    public long getMaxFixtureCacheSize() {
        return maxFixtureCacheSize;
    }

    public long getMaxCompetitorCacheSize() {
        return maxCompetitorCacheSize;
    }

    public long getMaxPlayerCacheSize() {
        return maxPlayerCacheSize;
    }

    /** 0.0.x's companion object; the constants are on the class. */
    public static final class Companion {
        private Companion() {}
    }
}
