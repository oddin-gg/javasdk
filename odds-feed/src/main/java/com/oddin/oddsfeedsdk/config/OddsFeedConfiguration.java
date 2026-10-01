package com.oddin.oddsfeedsdk.config;

import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/** A feed's configuration; {@link OddsFeedConfigurationBuilder} makes one. */
public final class OddsFeedConfiguration {

    public static final long DEFAULT_MAX_MATCH_CACHE_SIZE = 10_000L;
    public static final long DEFAULT_MAX_FIXTURE_CACHE_SIZE = 10_000L;
    public static final long DEFAULT_MAX_COMPETITOR_CACHE_SIZE = 20_000L;
    public static final long DEFAULT_MAX_PLAYER_CACHE_SIZE = 50_000L;

    static final Duration DEFAULT_HTTP_CLIENT_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_REST_CONCURRENCY_LIMIT = 16;
    /** The startup timeout, when none is set, is this many HTTP timeouts. */
    static final int STARTUP_TIMEOUTS = 3;

    static final int DEFAULT_AMQP_PREFETCH = 200;
    static final int MAX_AMQP_PREFETCH = 10_000;
    static final int DEFAULT_MAX_MESSAGE_SIZE = 1 << 20;

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
    private final Duration httpClientTimeout;
    private final int restConcurrencyLimit;
    private final Duration startupTimeout;
    private final int amqpPrefetch;
    private final @Nullable SSLContext messagingSslContext;
    private final int maxMessageSize;

    /**
     * Public because 0.0.x's constructor was, to Java callers; {@link OddsFeedConfigurationBuilder}
     * is the way to make one. What 0.0.x did not have takes its default.
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
        this(
                accessToken,
                defaultLocale,
                maxInactivitySeconds,
                maxRecoveryExecutionMinutes,
                sdkNodeId,
                exceptionHandlingStrategy,
                selectedEnvironment,
                initialSnapshotRecoveryInterval,
                maxMatchCacheSize,
                maxFixtureCacheSize,
                maxCompetitorCacheSize,
                maxPlayerCacheSize,
                DEFAULT_HTTP_CLIENT_TIMEOUT,
                DEFAULT_REST_CONCURRENCY_LIMIT,
                DEFAULT_HTTP_CLIENT_TIMEOUT.multipliedBy(STARTUP_TIMEOUTS),
                DEFAULT_AMQP_PREFETCH,
                DEFAULT_MAX_MESSAGE_SIZE,
                null);
    }

    OddsFeedConfiguration(
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
            long maxPlayerCacheSize,
            Duration httpClientTimeout,
            int restConcurrencyLimit,
            Duration startupTimeout,
            int amqpPrefetch,
            int maxMessageSize,
            @Nullable SSLContext messagingSslContext) {
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
        this.httpClientTimeout = httpClientTimeout;
        this.restConcurrencyLimit = restConcurrencyLimit;
        this.startupTimeout = startupTimeout;
        this.amqpPrefetch = amqpPrefetch;
        this.maxMessageSize = maxMessageSize;
        this.messagingSslContext = messagingSslContext;
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

    /**
     * The longest an API call takes, from waiting for its turn through every retry; a getter that
     * has to fetch waits at most this long. 30 seconds unless set. New in 1.0.
     */
    public Duration getHttpClientTimeout() {
        return httpClientTimeout;
    }

    /**
     * The most entity requests (matches, competitors, players, tournaments, fixtures, schedules)
     * in flight at once; recovery and description requests have their own. 16 unless set. New in
     * 1.0.
     */
    public int getRestConcurrencyLimit() {
        return restConcurrencyLimit;
    }

    /**
     * How long opening the feed keeps trying the API before it fails: three HTTP timeouts unless
     * set. New in 1.0.
     */
    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    /**
     * How many messages the broker hands a session before the session has acknowledged them: the
     * session's backlog in the SDK, and so, times the maximum message size, its memory. 200 unless
     * set. New in 1.0.
     */
    public int getAmqpPrefetch() {
        return amqpPrefetch;
    }

    /**
     * The largest message body the SDK decodes, in bytes; a larger one is reported as unparsable and
     * acknowledged. 1 MiB unless set. New in 1.0.
     */
    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    /**
     * The TLS context the feed's broker connection checks the broker's certificate with, or null for
     * the JVM's default. The certificate and the broker's host name are always checked; 0.0.x trusted
     * any certificate. New in 1.0.
     */
    public @Nullable SSLContext getMessagingSslContext() {
        return messagingSslContext;
    }

    /** 0.0.x's companion object; the constants are on the class. */
    public static final class Companion {
        private Companion() {}
    }
}
