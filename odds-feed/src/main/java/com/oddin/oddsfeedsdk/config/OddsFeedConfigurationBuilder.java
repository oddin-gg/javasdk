package com.oddin.oddsfeedsdk.config;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * Builds a feed's configuration: an environment and an access token are required, the rest has
 * defaults. Every setter throws a {@link NullPointerException} for a null argument at once, as
 * 0.0.x's did.
 */
public final class OddsFeedConfigurationBuilder {

    private static final int DEFAULT_MESSAGING_PORT = 5672;

    private @Nullable String accessToken;
    private Locale defaultLocale = Locale.ENGLISH;
    private @Nullable Environment selectedEnvironment;
    private int maxInactivitySeconds = OddsFeedConfiguration.DEFAULT_MAX_INACTIVITY_SECONDS;
    private int maxRecoveryExecutionMinutes = OddsFeedConfiguration.DEFAULT_MAX_RECOVERY_EXECUTION_MINUTES;
    private @Nullable Integer sdkNodeId;
    private ExceptionHandlingStrategy exceptionHandlingStrategy = ExceptionHandlingStrategy.THROW;
    private @Nullable Duration initialSnapshotRecoveryInterval;
    private long maxMatchCacheSize = OddsFeedConfiguration.DEFAULT_MAX_MATCH_CACHE_SIZE;
    private long maxFixtureCacheSize = OddsFeedConfiguration.DEFAULT_MAX_FIXTURE_CACHE_SIZE;
    private long maxCompetitorCacheSize = OddsFeedConfiguration.DEFAULT_MAX_COMPETITOR_CACHE_SIZE;
    private long maxPlayerCacheSize = OddsFeedConfiguration.DEFAULT_MAX_PLAYER_CACHE_SIZE;
    private Duration httpClientTimeout = OddsFeedConfiguration.DEFAULT_HTTP_CLIENT_TIMEOUT;
    private int restConcurrencyLimit = OddsFeedConfiguration.DEFAULT_REST_CONCURRENCY_LIMIT;
    private @Nullable Duration startupTimeout;
    private int amqpPrefetch = OddsFeedConfiguration.DEFAULT_AMQP_PREFETCH;
    private @Nullable SSLContext messagingSslContext;
    private int maxMessageSize = OddsFeedConfiguration.DEFAULT_MAX_MESSAGE_SIZE;
    private Duration shutdownTimeout = OddsFeedConfiguration.DEFAULT_SHUTDOWN_TIMEOUT;
    private Duration staleMessageLimit = OddsFeedConfiguration.DEFAULT_STALE_MESSAGE_LIMIT;
    private Duration staleMessageWindow = OddsFeedConfiguration.DEFAULT_STALE_MESSAGE_WINDOW;
    private String exchangeName = OddsFeedConfiguration.DEFAULT_EXCHANGE_NAME;
    private String replayExchangeName = OddsFeedConfiguration.DEFAULT_REPLAY_EXCHANGE_NAME;
    private boolean apiCallLogging;
    private Duration catalogStaleLimit = OddsFeedConfiguration.DEFAULT_CATALOG_STALE_LIMIT;
    private @Nullable Duration callbackStallLimit;
    private @Nullable Duration queueStallLimit;
    private Duration watchdogInterval = OddsFeedConfiguration.DEFAULT_WATCHDOG_INTERVAL;
    private Duration connectionDownLimit = OddsFeedConfiguration.DEFAULT_CONNECTION_DOWN_LIMIT;
    private List<Locale> preloadLocales = List.of();
    private boolean eagerEntityPreload;

    /** Public because 0.0.x's constructor was, to Java callers; {@code OddsFeed.getOddsFeedConfigurationBuilder()} makes one. */
    public OddsFeedConfigurationBuilder() {}

    public OddsFeedConfigurationBuilder selectProduction() {
        return selectProduction(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectProduction(Region region) {
        requireNonNull(region, "region");
        return select("mq." + region.getHost() + "oddin.gg", "api-mq." + region.getHost() + "oddin.gg");
    }

    public OddsFeedConfigurationBuilder selectIntegration() {
        return selectIntegration(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectIntegration(Region region) {
        requireNonNull(region, "region");
        return select(
                "mq.integration." + region.getHost() + "oddin.gg",
                "api-mq.integration." + region.getHost() + "oddin.gg");
    }

    public OddsFeedConfigurationBuilder selectTest() {
        return selectTest(Region.DEFAULT);
    }

    public OddsFeedConfigurationBuilder selectTest(Region region) {
        requireNonNull(region, "region");
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
        this.accessToken = requireNonNull(accessToken, "accessToken");
        return this;
    }

    public OddsFeedConfigurationBuilder setSDKNodeId(int sdkNodeId) {
        this.sdkNodeId = sdkNodeId;
        return this;
    }

    public OddsFeedConfigurationBuilder setExceptionHandlingStrategy(
            ExceptionHandlingStrategy exceptionHandlingStrategy) {
        this.exceptionHandlingStrategy = requireNonNull(exceptionHandlingStrategy, "exceptionHandlingStrategy");
        return this;
    }

    public OddsFeedConfigurationBuilder setInitialSnapshotRecoveryInterval(Duration interval) {
        this.initialSnapshotRecoveryInterval = requireNonNull(interval, "interval");
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
     * The longest an API call takes, from waiting for its turn through every retry. 30 seconds
     * unless set. The feed's watch over its own threads finds a callback stalled only past twice
     * this and 5 seconds, 30 seconds at least, unless {@link #setCallbackStallLimit} sets another
     * limit, so a callback waiting on a slow API is no stall. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is positive
     */
    public OddsFeedConfigurationBuilder setHttpClientTimeout(Duration timeout) {
        this.httpClientTimeout = positive(timeout, "HTTP client timeout");
        return this;
    }

    /**
     * The most entity requests in flight at once. 16 unless set. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is at least 1
     */
    public OddsFeedConfigurationBuilder setRestConcurrencyLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("REST concurrency limit must be at least 1, was " + limit);
        }
        this.restConcurrencyLimit = limit;
        return this;
    }

    /**
     * How long opening the feed keeps trying the API before it fails. Three HTTP timeouts unless
     * set. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is positive
     */
    public OddsFeedConfigurationBuilder setStartupTimeout(Duration timeout) {
        this.startupTimeout = positive(timeout, "startup timeout");
        return this;
    }

    /**
     * How many unacknowledged messages the broker hands each session. 200 unless set. New in 1.0.
     *
     * <p>A session holds at most this many messages, so its memory is at most this times the
     * {@linkplain #setMaxMessageSize maximum message size}, and one oversized body more while the
     * AMQP client reads it. A queue length limit the broker's operator sets must be above this
     * prefetch: the broker otherwise drops the oldest ready messages, which nothing in the SDK sees.
     *
     * @throws IllegalArgumentException unless it is 1 to 10 000; the broker reads 0 as unlimited
     */
    public OddsFeedConfigurationBuilder setAmqpPrefetch(int prefetch) {
        if (prefetch < 1 || prefetch > OddsFeedConfiguration.MAX_AMQP_PREFETCH) {
            throw new IllegalArgumentException(
                    "AMQP prefetch must be 1 to " + OddsFeedConfiguration.MAX_AMQP_PREFETCH + ", was " + prefetch);
        }
        this.amqpPrefetch = prefetch;
        return this;
    }

    /**
     * The largest message body decoded, in bytes. 1 MiB unless set. New in 1.0. A larger one is not
     * decoded: it is reported as unparsable, and to the global listener's {@code onCallbackFailure}.
     * The AMQP client reads a body of up to 64 MiB, or this and 1 MiB if more, before the SDK sees
     * its size, so an oversized message is counted rather than closing the connection.
     *
     * @throws IllegalArgumentException unless it is at least 1
     */
    public OddsFeedConfigurationBuilder setMaxMessageSize(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("maximum message size must be at least 1 byte, was " + bytes);
        }
        this.maxMessageSize = bytes;
        return this;
    }

    /**
     * The TLS context to check the feed broker's certificate with, instead of the JVM's default: for
     * a truststore of its own, or a proxy that inspects TLS. The certificate and the broker's host
     * name are always checked; 0.0.x trusted any certificate. New in 1.0.
     */
    public OddsFeedConfigurationBuilder setMessagingSslContext(SSLContext context) {
        this.messagingSslContext = requireNonNull(context, "context");
        return this;
    }

    /**
     * How long a producer may go without an alive, and a session may process it late, before the
     * producer counts as down. 20 seconds unless set, as in 0.0.x, which had no setter. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is more than the 10 seconds between a producer's
     *     alives
     */
    public OddsFeedConfigurationBuilder setMaxInactivitySeconds(int seconds) {
        if (seconds <= OddsFeedConfiguration.ALIVE_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("maximum inactivity must be more than the "
                    + OddsFeedConfiguration.ALIVE_INTERVAL_SECONDS + " seconds between a producer's alives, was "
                    + seconds);
        }
        this.maxInactivitySeconds = seconds;
        return this;
    }

    /**
     * How long a recovery may take before it counts as failed. 360 minutes unless set, as in 0.0.x,
     * which had no setter; it can be longer, not shorter. A producer's recovery whose snapshot
     * complete is lost is given up after five minutes already, so this bounds one that keeps coming,
     * and event recoveries. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is at least 360 minutes
     */
    public OddsFeedConfigurationBuilder setMaxRecoveryExecutionMinutes(int minutes) {
        if (minutes < OddsFeedConfiguration.DEFAULT_MAX_RECOVERY_EXECUTION_MINUTES) {
            throw new IllegalArgumentException("maximum recovery time must be at least "
                    + OddsFeedConfiguration.DEFAULT_MAX_RECOVERY_EXECUTION_MINUTES + " minutes, was " + minutes);
        }
        this.maxRecoveryExecutionMinutes = minutes;
        return this;
    }

    /**
     * How long {@code OddsFeed.close()} waits for the feed's threads, all of them together, before it
     * leaves a callback still running to end on its own. 5 seconds unless set. A failed {@code
     * open()} closes what it started within it too. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is positive and at most an hour
     */
    public OddsFeedConfigurationBuilder setShutdownTimeout(Duration timeout) {
        this.shutdownTimeout = positiveAtMost(timeout, OddsFeedConfiguration.MAX_SHUTDOWN_TIMEOUT, "shutdown timeout");
        return this;
    }

    /**
     * How old, by the producer's clock, a session's live messages may be before the safety net counts
     * the session behind. 2 minutes unless set. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is more than the 10 seconds between a producer's
     *     alives, since the delivery's own jitter would make a healthy session look stale under a
     *     smaller one, and at most a day
     */
    public OddsFeedConfigurationBuilder setStaleMessageLimit(Duration limit) {
        requireNonNull(limit, "stale-message limit");
        var alives = Duration.ofSeconds(OddsFeedConfiguration.ALIVE_INTERVAL_SECONDS);
        if (limit.compareTo(alives) <= 0) {
            throw new IllegalArgumentException("stale-message limit must be more than the "
                    + OddsFeedConfiguration.ALIVE_INTERVAL_SECONDS + " seconds between a producer's alives, was "
                    + limit);
        }
        this.staleMessageLimit = atMost(limit, OddsFeedConfiguration.MAX_STALE_MESSAGE_DURATION, "stale-message limit");
        return this;
    }

    /**
     * How long a session's live messages must stay older than the {@linkplain #setStaleMessageLimit
     * limit} before the safety net asks for a recovery of the session's producers and, once the API
     * has accepted them, replaces the session's channel, dropping the backlog. 1 minute unless set.
     * New in 1.0.
     *
     * @throws IllegalArgumentException unless it is at least the recovery's tick of a second, which
     *     it is measured by, and at most a day
     */
    public OddsFeedConfigurationBuilder setStaleMessageWindow(Duration window) {
        requireNonNull(window, "stale-message window");
        if (window.compareTo(OddsFeedConfiguration.RECOVERY_TICK) < 0) {
            throw new IllegalArgumentException("stale-message window must be at least the recovery's tick of "
                    + OddsFeedConfiguration.RECOVERY_TICK + ", was " + window);
        }
        this.staleMessageWindow =
                atMost(window, OddsFeedConfiguration.MAX_STALE_MESSAGE_DURATION, "stale-message window");
        return this;
    }

    /**
     * The exchange the feed's queues are bound to. {@code oddinfeed} unless set, as in 0.0.x. New in
     * 1.0.
     *
     * @throws IllegalArgumentException when it is blank, or longer than 255 bytes in UTF-8
     */
    public OddsFeedConfigurationBuilder setExchangeName(String name) {
        this.exchangeName = exchange(name, "exchange name");
        return this;
    }

    /**
     * The exchange a replay session's queue is bound to. {@code oddinreplay} unless set, as in
     * 0.0.x. New in 1.0.
     *
     * @throws IllegalArgumentException when it is blank, or longer than 255 bytes in UTF-8
     */
    public OddsFeedConfigurationBuilder setReplayExchangeName(String name) {
        this.replayExchangeName = exchange(name, "replay exchange name");
        return this;
    }

    /**
     * Whether every attempt of an API call is logged at INFO, with its method, URI, status and
     * latency. Off unless set; the global listener's {@code onApiCall} hears them either way. New in
     * 1.0.
     */
    public OddsFeedConfigurationBuilder setApiCallLogging(boolean enabled) {
        this.apiCallLogging = enabled;
        return this;
    }

    /**
     * How long a catalog - the market descriptions, the void reasons, the match statuses - may serve a
     * value stale, its refreshes failing, before {@code getHealth()} counts it degraded. An hour
     * unless set. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is positive and at most a day
     */
    public OddsFeedConfigurationBuilder setCatalogStaleLimit(Duration limit) {
        this.catalogStaleLimit = positiveAtMost(limit, OddsFeedConfiguration.MAX_HEALTH_LIMIT, "catalog stale limit");
        return this;
    }

    /**
     * How long one of the feed's threads may run one callback, or one step of its own, before the
     * feed's watch over its threads counts it stalled, logs it and tells {@code onHealthEvent}.
     * Unless set, {@code max(30 s, 2 × HTTP timeout + 5 s)}: 65 s for the default HTTP timeout. New
     * in 1.0.
     *
     * <p>{@link #build} refuses a limit under {@code 2 × HTTP timeout + 5 s}: a getter in a callback
     * may wait for the API twice, each time the HTTP timeout and a second, and a second more, so a
     * shorter limit would call a slow API a stall, whose remedy, a new feed, would not speed it up.
     *
     * @throws IllegalArgumentException unless it is positive and at most a day
     */
    public OddsFeedConfigurationBuilder setCallbackStallLimit(Duration limit) {
        this.callbackStallLimit = positiveAtMost(limit, OddsFeedConfiguration.MAX_HEALTH_LIMIT, "callback stall limit");
        return this;
    }

    /**
     * How long a thread's queue may stand still, not empty, before the feed's watch counts the thread
     * stalled; also how late the watch's own next look may be before the watch counts itself
     * stalled. Unless set, the {@linkplain #setCallbackStallLimit callback stall limit}'s default;
     * {@link #build} refuses it under the same {@code 2 × HTTP timeout + 5 s}. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is positive and at most a day
     */
    public OddsFeedConfigurationBuilder setQueueStallLimit(Duration limit) {
        this.queueStallLimit = positiveAtMost(limit, OddsFeedConfiguration.MAX_HEALTH_LIMIT, "queue stall limit");
        return this;
    }

    /**
     * How often the feed's watch over its own threads looks. Every 5 seconds unless set. New in 1.0.
     *
     * @throws IllegalArgumentException unless it is a second to a minute: each look searches the JVM's
     *     threads for a deadlock, which stops them all for a moment
     */
    public OddsFeedConfigurationBuilder setWatchdogInterval(Duration interval) {
        requireNonNull(interval, "watchdog interval");
        if (interval.compareTo(OddsFeedConfiguration.MIN_WATCHDOG_INTERVAL) < 0
                || interval.compareTo(OddsFeedConfiguration.MAX_WATCHDOG_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "watchdog interval must be " + OddsFeedConfiguration.MIN_WATCHDOG_INTERVAL + " to "
                            + OddsFeedConfiguration.MAX_WATCHDOG_INTERVAL + ", was " + interval);
        }
        this.watchdogInterval = interval;
        return this;
    }

    /**
     * How long the broker connection may be down before {@code getHealth()} counts it degraded and
     * {@code onHealthEvent} is told; it is healthy again once it is up. 60 seconds unless set. New in
     * 1.0.
     *
     * @throws IllegalArgumentException unless it is positive and at most a day
     */
    public OddsFeedConfigurationBuilder setConnectionDownLimit(Duration limit) {
        this.connectionDownLimit =
                positiveAtMost(limit, OddsFeedConfiguration.MAX_HEALTH_LIMIT, "connection down limit");
        return this;
    }

    /**
     * The locale a getter without one reads in, and the one fixtures and feed messages are built in.
     * English unless set, as in 0.0.x, which had no setter. New in 1.0.
     *
     * @throws IllegalArgumentException for a locale without a language, such as {@link Locale#ROOT}:
     *     the API is asked by the language
     */
    public OddsFeedConfigurationBuilder setDefaultLocale(Locale locale) {
        this.defaultLocale = language(locale, "locale");
        return this;
    }

    /**
     * The locales whose catalogs - the market descriptions, the match statuses, the sports, and the
     * void reasons once - {@code open()} starts loading in the background, so the first messages and
     * reads find them; with the {@linkplain #setEagerEntityPreload eager entity preload}, also the
     * locales a message's match is loaded in, next to the default locale. None unless set: 0.0.x
     * loaded each catalog on its first read, as 1.0 still does for any locale. A locale given twice
     * counts once. The caches keep each locale apart, as 0.0.x did, so {@code en_US} and {@code en}
     * are both loaded, though the API, asked by the language, answers both alike. New in 1.0.
     *
     * @throws IllegalArgumentException for a locale without a language
     */
    public OddsFeedConfigurationBuilder setPreloadLocales(List<Locale> locales) {
        requireNonNull(locales, "locales");
        var each = new LinkedHashSet<Locale>();
        for (Locale locale : locales) {
            each.add(language(locale, "locales"));
        }
        this.preloadLocales = List.copyOf(each);
        return this;
    }

    /**
     * Whether the match each feed message names is loaded in the background as the message arrives,
     * before the session takes it, in the default locale and the {@linkplain #setPreloadLocales
     * preload locales}, so the callback reads it warm instead of waiting for the API. Off unless set,
     * as in 0.0.x, which loaded an entity on its first read. New in 1.0.
     *
     * <p>The loads run on the SDK's background loaders, never on the thread that delivers or handles
     * a message, and never delay a message: one already under way or queued for the same match and
     * locale is not queued again, a full queue drops the load and counts it with the side-loads in
     * {@code getHealth()}, and a load that fails is logged and counted there too. A callback that
     * reads the match before its load is done joins it.
     */
    public OddsFeedConfigurationBuilder setEagerEntityPreload(boolean enabled) {
        this.eagerEntityPreload = enabled;
        return this;
    }

    private static Locale language(Locale locale, String what) {
        if (requireNonNull(locale, what).getLanguage().isEmpty()) {
            throw new IllegalArgumentException(what + " must name a language, was \"" + locale + "\"");
        }
        return locale;
    }

    private static Duration positive(Duration duration, String what) {
        if (!requireNonNull(duration, what).isPositive()) {
            throw new IllegalArgumentException(what + " must be positive, was " + duration);
        }
        return duration;
    }

    private static Duration positiveAtMost(Duration duration, Duration max, String what) {
        return atMost(positive(duration, what), max, what);
    }

    private static Duration atMost(Duration duration, Duration max, String what) {
        if (duration.compareTo(max) > 0) {
            throw new IllegalArgumentException(what + " must be at most " + max + ", was " + duration);
        }
        return duration;
    }

    private static String exchange(String name, String what) {
        requireNonNull(name, "name");
        if (name.isBlank()
                || name.getBytes(StandardCharsets.UTF_8).length > OddsFeedConfiguration.MAX_EXCHANGE_NAME_BYTES) {
            throw new IllegalArgumentException(what + " must not be blank, nor longer than "
                    + OddsFeedConfiguration.MAX_EXCHANGE_NAME_BYTES + " bytes in UTF-8, was \"" + name + "\"");
        }
        return name;
    }

    /**
     * The configuration.
     *
     * @throws IllegalArgumentException without an access token or an environment, or with a stall
     *     limit set under {@code 2 × HTTP timeout + 5 s}
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
        Duration startup = startupTimeout;
        Duration callbackStall = stallLimit(callbackStallLimit, "callback stall limit");
        Duration queueStall = stallLimit(queueStallLimit, "queue stall limit");
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
                maxPlayerCacheSize,
                httpClientTimeout,
                restConcurrencyLimit,
                startup != null ? startup : httpClientTimeout.multipliedBy(OddsFeedConfiguration.STARTUP_TIMEOUTS),
                amqpPrefetch,
                maxMessageSize,
                messagingSslContext,
                shutdownTimeout,
                staleMessageLimit,
                staleMessageWindow,
                exchangeName,
                replayExchangeName,
                apiCallLogging,
                catalogStaleLimit,
                callbackStall,
                queueStall,
                watchdogInterval,
                connectionDownLimit,
                preloadLocales,
                eagerEntityPreload);
    }

    /** The stall limit set, if it sits above the SDK's own waits for the API; the derived one if none. */
    private Duration stallLimit(@Nullable Duration set, String what) {
        if (set == null) {
            return OddsFeedConfiguration.stallLimitFor(httpClientTimeout);
        }
        var least = OddsFeedConfiguration.leastStallLimit(httpClientTimeout);
        if (set.compareTo(least) < 0) {
            throw new IllegalArgumentException(what + " must be at least " + least
                    + ", 2 s above the longest the SDK itself waits for the API with an HTTP timeout of "
                    + httpClientTimeout + ", was " + set);
        }
        return set;
    }
}
