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

    /** 0.0.x's 20 seconds, which it had no setter for. */
    static final int DEFAULT_MAX_INACTIVITY_SECONDS = 20;
    /** A producer sends an alive this often; a shorter maximum inactivity would take it down between two. */
    static final int ALIVE_INTERVAL_SECONDS = 10;

    /** 0.0.x's six hours, which it had no setter for, and the least the setter takes. */
    static final int DEFAULT_MAX_RECOVERY_EXECUTION_MINUTES = 360;

    /** How long the feed's close waits for its threads, all of them together, unless set. */
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    /** The longest shutdown timeout the builder takes. */
    static final Duration MAX_SHUTDOWN_TIMEOUT = Duration.ofHours(1);

    /** How old live messages may be before the safety net counts them stale, unless set. */
    static final Duration DEFAULT_STALE_MESSAGE_LIMIT = Duration.ofMinutes(2);
    /** How long they must stay stale before the safety net acts, unless set. */
    static final Duration DEFAULT_STALE_MESSAGE_WINDOW = Duration.ofMinutes(1);
    /** The most the stale-message limit and window take: a day is as good as off. */
    static final Duration MAX_STALE_MESSAGE_DURATION = Duration.ofDays(1);

    /** The exchange 0.0.x bound the feed's queues to. */
    static final String DEFAULT_EXCHANGE_NAME = "oddinfeed";
    /** The exchange 0.0.x bound a replay session's queue to. */
    static final String DEFAULT_REPLAY_EXCHANGE_NAME = "oddinreplay";
    /** An AMQP short string's limit, in UTF-8 bytes; the broker refuses a longer exchange name. */
    static final int MAX_EXCHANGE_NAME_BYTES = 255;

    /** How long a catalog may serve a value stale, its refreshes failing, before it is degraded, unless set. */
    static final Duration DEFAULT_CATALOG_STALE_LIMIT = Duration.ofHours(1);
    /** How long the broker connection may be down before it is degraded, unless set. */
    static final Duration DEFAULT_CONNECTION_DOWN_LIMIT = Duration.ofSeconds(60);
    /** The most the catalogs' and the connection's limits, and the stall limits, take: a day is as good as off. */
    static final Duration MAX_HEALTH_LIMIT = Duration.ofDays(1);
    /** The least a stall limit is unless set, whatever the HTTP timeout. */
    static final Duration STALL_LIMIT_FLOOR = Duration.ofSeconds(30);
    /**
     * How much longer than its deadline a caller waits for one of the SDK's loads, as the loaders and
     * an event recovery's caller do.
     */
    static final Duration LOAD_MARGIN = Duration.ofSeconds(1);
    /** How far a stall limit stays above the longest the SDK itself waits for the API. */
    static final Duration ABOVE_THE_SDKS_WAITS = Duration.ofSeconds(2);
    /** How often the feed's watch over its own threads looks, unless set. */
    static final Duration DEFAULT_WATCHDOG_INTERVAL = Duration.ofSeconds(5);
    /** The least watchdog interval: each look searches the JVM's threads for a deadlock, at a safepoint. */
    static final Duration MIN_WATCHDOG_INTERVAL = Duration.ofSeconds(1);
    /** The longest watchdog interval the builder takes. */
    static final Duration MAX_WATCHDOG_INTERVAL = Duration.ofMinutes(1);

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
    private final Duration shutdownTimeout;
    private final Duration staleMessageLimit;
    private final Duration staleMessageWindow;
    private final String exchangeName;
    private final String replayExchangeName;
    private final boolean apiCallLogging;
    private final Duration catalogStaleLimit;
    private final Duration callbackStallLimit;
    private final Duration queueStallLimit;
    private final Duration watchdogInterval;
    private final Duration connectionDownLimit;

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
                null,
                DEFAULT_SHUTDOWN_TIMEOUT,
                DEFAULT_STALE_MESSAGE_LIMIT,
                DEFAULT_STALE_MESSAGE_WINDOW,
                DEFAULT_EXCHANGE_NAME,
                DEFAULT_REPLAY_EXCHANGE_NAME,
                false,
                DEFAULT_CATALOG_STALE_LIMIT,
                stallLimitFor(DEFAULT_HTTP_CLIENT_TIMEOUT),
                stallLimitFor(DEFAULT_HTTP_CLIENT_TIMEOUT),
                DEFAULT_WATCHDOG_INTERVAL,
                DEFAULT_CONNECTION_DOWN_LIMIT);
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
            @Nullable SSLContext messagingSslContext,
            Duration shutdownTimeout,
            Duration staleMessageLimit,
            Duration staleMessageWindow,
            String exchangeName,
            String replayExchangeName,
            boolean apiCallLogging,
            Duration catalogStaleLimit,
            Duration callbackStallLimit,
            Duration queueStallLimit,
            Duration watchdogInterval,
            Duration connectionDownLimit) {
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
        this.shutdownTimeout = shutdownTimeout;
        this.staleMessageLimit = staleMessageLimit;
        this.staleMessageWindow = staleMessageWindow;
        this.exchangeName = exchangeName;
        this.replayExchangeName = replayExchangeName;
        this.apiCallLogging = apiCallLogging;
        this.catalogStaleLimit = catalogStaleLimit;
        this.callbackStallLimit = callbackStallLimit;
        this.queueStallLimit = queueStallLimit;
        this.watchdogInterval = watchdogInterval;
        this.connectionDownLimit = connectionDownLimit;
    }

    /**
     * The longest the SDK itself waits for the API in one getter, with an HTTP timeout of {@code
     * httpTimeout}: a read loads twice at most, each load waiting the timeout and its margin, and the
     * read waits its own margin over both, {@code 2 × (timeout + 1 s) + 1 s}.
     */
    static Duration longestApiWait(Duration httpTimeout) {
        return httpTimeout.plus(LOAD_MARGIN).multipliedBy(2).plus(LOAD_MARGIN);
    }

    /** The least a stall limit can be with this HTTP timeout: 2 s above the SDK's own longest wait. */
    static Duration leastStallLimit(Duration httpTimeout) {
        return longestApiWait(httpTimeout).plus(ABOVE_THE_SDKS_WAITS);
    }

    /** A stall limit unless set: {@code max(30 s, 2 × timeout + 5 s)}, 65 s for the default timeout. */
    static Duration stallLimitFor(Duration httpTimeout) {
        var least = leastStallLimit(httpTimeout);
        return least.compareTo(STALL_LIMIT_FLOOR) < 0 ? STALL_LIMIT_FLOOR : least;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * How long a producer may go without an alive, and a session may process it late, before the
     * producer counts as down: 20 seconds unless set.
     */
    public int getMaxInactivitySeconds() {
        return maxInactivitySeconds;
    }

    /**
     * How long a recovery may take before it counts as failed: 360 minutes unless set. A producer's
     * recovery whose snapshot complete is lost is given up after five minutes already, so this
     * bounds one that keeps coming, and event recoveries.
     */
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

    /**
     * How long {@code OddsFeed.close()} waits for the feed's threads, all of them together, before
     * it leaves a callback still running to end on its own: 5 seconds unless set. A failed {@code
     * open()} closes what it started within it too. New in 1.0.
     */
    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * How old, by the producer's clock, live messages may be before the safety net counts a session
     * behind: 2 minutes unless set. New in 1.0.
     */
    public Duration getStaleMessageLimit() {
        return staleMessageLimit;
    }

    /**
     * How long a session's live messages must stay older than the {@linkplain #getStaleMessageLimit
     * limit} before the safety net asks for a recovery and replaces the session's channel: 1 minute
     * unless set. New in 1.0.
     */
    public Duration getStaleMessageWindow() {
        return staleMessageWindow;
    }

    /** The exchange the feed's queues are bound to: {@code oddinfeed} unless set. New in 1.0. */
    public String getExchangeName() {
        return exchangeName;
    }

    /** The exchange a replay session's queue is bound to: {@code oddinreplay} unless set. New in 1.0. */
    public String getReplayExchangeName() {
        return replayExchangeName;
    }

    /**
     * Whether every attempt of an API call is logged at INFO, with its method, URI, status and
     * latency: not unless set. The global listener's {@code onApiCall} hears them either way. New in
     * 1.0.
     */
    public boolean isApiCallLogging() {
        return apiCallLogging;
    }

    /**
     * How long a catalog - the market descriptions, the void reasons, the match statuses - may serve a
     * value stale, its refreshes failing, before {@code getHealth()} counts it degraded: an hour unless
     * set. New in 1.0.
     */
    public Duration getCatalogStaleLimit() {
        return catalogStaleLimit;
    }

    /**
     * How long one of the feed's threads may run one callback, or one step of its own, before the
     * feed's watch over its threads counts it stalled: unless set, {@code max(30 s, 2 × HTTP timeout
     * + 5 s)}, 65 s for the default HTTP timeout, so a callback waiting on a slow API is no stall.
     * New in 1.0.
     */
    public Duration getCallbackStallLimit() {
        return callbackStallLimit;
    }

    /**
     * How long a thread's queue may stand still, not empty, before the feed's watch counts the thread
     * stalled; also how late the watch's own next look may be. Unless set, the {@linkplain
     * #getCallbackStallLimit callback stall limit}'s default. New in 1.0.
     */
    public Duration getQueueStallLimit() {
        return queueStallLimit;
    }

    /** How often the feed's watch over its own threads looks: every 5 seconds unless set. New in 1.0. */
    public Duration getWatchdogInterval() {
        return watchdogInterval;
    }

    /**
     * How long the broker connection may be down before {@code getHealth()} counts it degraded: 60
     * seconds unless set. New in 1.0.
     */
    public Duration getConnectionDownLimit() {
        return connectionDownLimit;
    }

    /** 0.0.x's companion object; the constants are on the class. */
    public static final class Companion {
        private Companion() {}
    }
}
