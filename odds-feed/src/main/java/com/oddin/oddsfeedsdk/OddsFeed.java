package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.api.BookmakerDetail;
import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.feed.FeedCore;
import com.oddin.oddsfeedsdk.internal.feed.HealthMonitor;
import com.oddin.oddsfeedsdk.internal.feed.OpenFeed;
import com.oddin.oddsfeedsdk.internal.feed.Watchdog;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.session.SessionRegistry;
import com.oddin.oddsfeedsdk.internal.session.SessionSpec;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SDK's entry point: one feed, its sessions and its managers.
 *
 * <p>The feed starts as 0.0.x started it, on the first call of a manager getter or of {@link
 * #getSessionBuilder}: it asks the API who the bookmaker is and which producers there are, within
 * the startup timeout, and builds the managers. That happens once, however many threads call at
 * once. It tries again until the startup timeout, three HTTP timeouts unless set, so with the API
 * down a start takes that long to fail. When it fails the call throws an {@link InitException}
 * saying "Failed to init odds feed", with the reason as its cause, and the next call tries again.
 * The managers work before the feed opens; {@link #close} releases what the start built. The
 * feed's events thread and the watch over its threads stay from its first start to its close, so a
 * feed the client gives up on is closed.
 *
 * <p>Sessions are built before {@link #open}, which connects to the broker and starts delivering to
 * them, once: see {@link #open} and {@link #close}.
 */
public final class OddsFeed {

    private static final Logger LOG = LoggerFactory.getLogger(OddsFeed.class);

    private static final String INIT_FAILED = "Failed to init odds feed";

    /** What a second {@link #open} throws, word for word as 0.0.x threw it. */
    static final String OPENED_ALREADY = "feed cannot already opened";

    /**
     * Keeps {@code OddsFeed.Companion.getOddsFeedConfigurationBuilder()} compiling: 0.0.x was
     * Kotlin, and that is how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    private final OddsFeedConfiguration configuration;
    private final SessionRegistry sessions;

    /**
     * The client's events, from the first start on: one thread for every start the feed makes, so a
     * start that fails leaves no callback running beside the next one's, and the feed's close waits
     * for it.
     */
    private final EventsDispatcher events;

    /** What {@link #getHealth} reads, and what tells a part's change of health; for the feed's life. */
    private final HealthMonitor health;

    /** The watch over the feed's threads, from its first start to its close, on its timer thread. */
    private final Watchdog watchdog;

    /** The event recoveries, over whatever the feed is when they are asked for. */
    private final RecoveryManager recovery = new FeedRecovery();
    /** Held by the one start under way, so callers that come meanwhile wait for it, not start again. */
    private final ReentrantLock starting = new ReentrantLock();

    /**
     * Whether the first start has started the events dispatcher and the watchdog; guarded by {@link
     * #starting}.
     */
    private boolean eventsStarted;

    /** Guards the fields below, briefly: {@link #close} never waits for a start, nor for an open. */
    private final ReentrantLock state = new ReentrantLock();

    /** What the start built; null until it succeeds. Read without the lock once set. */
    private volatile @Nullable FeedCore core;

    /** The REST client of the start under way, which closing the feed closes to end the start. */
    private @Nullable ApiClient startingWith;

    /** What {@link #open} added, once it has built it; null before and after an open that failed. */
    private @Nullable OpenFeed running;

    /** Whether {@link #open} has taken the sessions: it is one-shot from then on, whatever comes of it. */
    private boolean opened;

    private boolean closed;

    public OddsFeed(GlobalEventsListener listener, OddsFeedConfiguration configuration) {
        this(listener, configuration, null, null, HealthMonitor.Log.SLF4J);
    }

    public OddsFeed(
            GlobalEventsListener listener, OddsFeedConfiguration configuration, OddsFeedExtListener extListener) {
        this(listener, configuration, requireNonNull(extListener, "extListener"), null, HealthMonitor.Log.SLF4J);
    }

    /**
     * With the watchdog's limits and the health's log a test sets.
     *
     * @param limits the watchdog's limits; null for the configuration's
     */
    OddsFeed(
            GlobalEventsListener listener,
            OddsFeedConfiguration configuration,
            @Nullable OddsFeedExtListener extListener,
            Watchdog.@Nullable Limits limits,
            HealthMonitor.Log log) {
        this(listener, configuration, extListener, limits, log, configuration.getCatalogStaleLimit());
    }

    /**
     * With the watchdog's limits, the health's log and the catalogs' stale limit a test sets.
     *
     * @param limits the watchdog's limits; null for the configuration's
     */
    OddsFeed(
            GlobalEventsListener listener,
            OddsFeedConfiguration configuration,
            @Nullable OddsFeedExtListener extListener,
            Watchdog.@Nullable Limits limits,
            HealthMonitor.Log log,
            Duration catalogStaleLimit) {
        requireNonNull(listener, "listener");
        this.configuration = requireNonNull(configuration, "configuration");
        this.sessions = new SessionRegistry(extListener);
        this.events = events(listener, extListener);
        this.health = new HealthMonitor(
                events, sessions::session, catalogStaleLimit, configuration.getConnectionDownLimit(), log);
        this.watchdog = new Watchdog(
                health,
                () -> Watchdog.parts(events, running()),
                this::read,
                limits != null ? limits : Watchdog.Limits.of(configuration));
    }

    /**
     * Not started yet. Its producers and sessions are looked up as an event is delivered: the
     * producers once the feed has started, the sessions once it has opened.
     */
    private EventsDispatcher events(GlobalEventsListener listener, @Nullable OddsFeedExtListener extListener) {
        return new EventsDispatcher(listener, extListener, this::producer, sessions::session);
    }

    private @Nullable Producer producer(long id) {
        FeedCore built = core;
        return built == null ? null : built.producers().getProducer(id);
    }

    public static OddsFeedConfigurationBuilder getOddsFeedConfigurationBuilder() {
        return new OddsFeedConfigurationBuilder();
    }

    /**
     * The SDK's version, as it reports it to the API and the broker: {@code 1.0.0}, or a version
     * marked {@code -dev} for a build that is not a release. New in 1.0, for a client to log what
     * it runs.
     */
    public static String getSdkVersion() {
        return SdkVersion.version();
    }

    /**
     * A builder of this feed's sessions, starting the feed as every manager getter does. Sessions
     * are built before {@link #open}; once it has run, {@code build()} and {@code buildReplay()}
     * throw {@link IllegalStateException}.
     */
    public OddsFeedSessionBuilder getSessionBuilder() {
        core();
        return sessions.builder();
    }

    public MarketDescriptionManager getMarketDescriptionManager() {
        return core().descriptions();
    }

    public SportsInfoManager getSportsInfoManager() {
        return core().sportsInfo();
    }

    public ProducerManager getProducerManager() {
        return core().producers();
    }

    public BookmakerDetail getBookMakerDetail() {
        return core().bookmaker();
    }

    public ReplayManager getReplayManager() {
        return core().replay();
    }

    /**
     * Event recoveries. Once the feed is open, a request waits on the caller's thread for the API's
     * answer, for the HTTP timeout and a second at most, and returns the request id, or null when it
     * was not accepted. Until the feed opens, a request is not accepted: it returns null, since no
     * session's queue exists yet that the recovered messages could reach; nor on a replay feed,
     * which runs no recovery, or once the feed is closed.
     */
    public RecoveryManager getRecoveryManager() {
        core();
        return recovery;
    }

    /**
     * The feed's health now: its state, the state of each part, and the counters of what the SDK
     * deliberately gives up, for a health check or a metrics exporter. Cheap, and safe from any
     * thread, a callback included: it reads counters and flags, never waits for the feed, and does
     * not start it. Before the feed starts it has no part and counts nothing; once started it has the
     * events, the catalogs, the timers and the JVM's threads, and once open the connection, the
     * consumer and the sessions, and the alives and the recovery unless it is a replay feed. Once closed it still reads
     * what the feed counted, and each part as the watch last found it, but the timers healthy: a
     * watch stopped is not one wedged; and the connection as it was at the close, healthy unless it
     * had been down for longer than its limit by then, with no change told or logged after.
     *
     * <p>From its start to its close the feed watches its own threads, every 5 seconds unless the
     * configuration sets another interval: a part that has been in one callback, or whose queue has
     * not moved while not empty, for longer than the configuration's stall limits is stalled, and so
     * are the JVM's threads while a deadlock holds some; each is healthy again once that ends. The
     * limits are never under the longest the SDK itself can wait for the API in one getter, so a slow
     * API is no stall; unless set, they are twice the HTTP client timeout and 5 seconds, 30 seconds
     * at least: 65 seconds for the default timeout. A catalog that has served a value stale for its
     * limit or more, an hour unless set, is degraded, and so are a session lagging and the broker
     * connection down for longer than its limit, 60 seconds unless set. Each change is logged - a
     * stall as an error - and told to
     * {@code onHealthEvent}, until the feed closes; a session's lagging as it changes. The feed never
     * interrupts a stalled thread: the remedy is to close it and open a new one. This call looks at
     * the parts again itself, so it finds a stall even when the watch's own thread is the one wedged,
     * and the timers stalled then. New in 1.0.
     */
    public FeedHealth getHealth() {
        // on the caller's thread too: the watchdog cannot see its own thread wedge
        watchdog.recheck();
        return read();
    }

    /** The health as it is now, and any change it finds told. */
    private FeedHealth read() {
        return health.health(core, running());
    }

    /**
     * Opens the feed for the sessions built so far: starts the feed if it has not started, checks
     * the sessions' interests combine, disables the producers no session asks for, then connects to
     * the broker and starts delivering. All or nothing: when a step fails, what it started is
     * closed again, nothing is left running or connected, and this throws; the managers stay, until
     * {@link #close}. One-shot: once the sessions are taken, a second call throws, whatever came of
     * the first, where 0.0.x let a failed open be tried again; the client closes the feed and makes
     * a new one. From the open to
     * the close the feed keeps one non-daemon thread, as 0.0.x did, so the JVM does not exit while
     * the feed is open, not even while it reconnects; a feed that is never closed keeps it running.
     *
     * @throws IllegalStateException without a session, with 0.0.x's message; the feed can then still
     *     be opened once one is built
     * @throws com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination when the
     *     sessions' interests do not combine, with 0.0.x's messages
     * @throws InitException when the feed cannot start or reach the broker, when it was opened
     *     already ("feed cannot already opened", as in 0.0.x), or when it is closed
     */
    public void open() {
        var core = core();
        List<SessionSpec> specs;
        state.lock();
        try {
            if (closed) {
                throw closedBeforeOpen();
            }
            if (opened) {
                throw new InitException(OPENED_ALREADY, null);
            }
            specs = sessions.open();
            opened = true;
        } finally {
            state.unlock();
        }
        var producers = core.producers();
        // the recovery starts are read as the feed opens: a later one would be lost unsaid
        producers.opened();
        var plan = Sessions.plan(specs, producers.getAvailableProducers(), configuration.getSdkNodeId());
        plan.disabledProducers().forEach(id -> producers.setProducerState(id, false));
        var run = OpenFeed.build(core, plan, configuration, health);
        state.lock();
        try {
            if (closed) {
                // close() saw nothing to close, and nothing has started
                throw closedBeforeOpen();
            }
            running = run;
        } finally {
            state.unlock();
        }
        try {
            run.start();
        } catch (RuntimeException e) {
            // it closed what it started; there is nothing open for close() to stop
            state.lock();
            try {
                if (running == run) {
                    running = null;
                }
            } finally {
                state.unlock();
            }
            throw e;
        }
        LOG.info("Odds feed opened with {} session(s)", specs.size());
    }

    /**
     * Stops delivering and releases everything the feed has: the broker connection, the sessions'
     * threads once their callbacks return, and what the start built. A message a session takes once
     * it has begun reaches no listener, as in 0.0.x: it is dropped, and the resume point covers it.
     * Ends a start or an open under way, which then fails. Waits for the feed's threads within one
     * shutdown timeout, five seconds unless the configuration sets it, for all of them together; an
     * interrupt does not cut short the wait for the sessions and the recovery, so the resume points
     * are final when it returns. A callback still running then is left to end on its own and said so
     * in the log. From one of the feed's own callbacks it does not wait for that callback. Closing
     * twice does nothing more, and closing a feed whose start failed has nothing to release and logs
     * nothing. Once closed, the feed does not start or open again; the managers it had already handed
     * out stay, closed.
     */
    public void close() {
        @Nullable FeedCore built;
        @Nullable ApiClient calling;
        @Nullable OpenFeed run;
        state.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            built = core;
            calling = startingWith;
            run = running;
        } finally {
            state.unlock();
        }
        var deadline = System.nanoTime() + configuration.getShutdownTimeout().toNanos();
        // first, so it finds no part stopping for stalled
        watchdog.stop();
        // the connection as it is now: closing it is no loss, and its time down stops counting
        health.closed();
        if (built == null && calling == null && run == null) {
            // never started, or its start failed and released what it built: nothing to say, but a
            // callback of the failed start's may still run
            events.stop();
            watchdog.awaitStop(deadline);
            events.awaitStop(deadline);
            return;
        }
        if (calling != null) {
            calling.close();
        }
        // every thread is told before any is waited for, so they stop together, within one deadline
        if (run != null) {
            run.stop();
        }
        if (built != null) {
            // the REST client, so a callback waiting on a call returns at once
            built.close();
        }
        events.stop();
        watchdog.awaitStop(deadline);
        if (run != null) {
            run.awaitStop(deadline);
        }
        events.awaitStop(deadline);
        LOG.debug("Odds feed closed");
    }

    /**
     * What the start built, starting the feed if it has not started: the first caller starts it,
     * and every other waits for that start and then has its result, or starts again if it failed.
     *
     * @throws InitException when the start fails, or the feed was closed before it succeeded
     */
    private FeedCore core() {
        FeedCore built = core;
        if (built != null) {
            return built;
        }
        starting.lock();
        try {
            built = core;
            if (built != null) {
                return built;
            }
            if (isClosed()) {
                throw closedBeforeStart();
            }
            if (!eventsStarted) {
                events.start();
                watchdog.start();
                eventsStarted = true;
            }
            try {
                built = FeedCore.start(configuration, events, this::startingWith);
            } catch (InitException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new InitException(INIT_FAILED, e);
            } finally {
                startingWith(null);
            }
            if (!publish(built)) {
                built.close();
                throw closedBeforeStart();
            }
            return built;
        } finally {
            starting.unlock();
        }
    }

    /** Records the start's REST client, or closes it when the feed was closed meanwhile. */
    private void startingWith(@Nullable ApiClient api) {
        state.lock();
        try {
            if (!closed || api == null) {
                startingWith = api;
                return;
            }
        } finally {
            state.unlock();
        }
        api.close();
    }

    /** Whether the feed was still open to take what the start built. */
    private boolean publish(FeedCore built) {
        state.lock();
        try {
            if (closed) {
                return false;
            }
            core = built;
            return true;
        } finally {
            state.unlock();
        }
    }

    /** The client's events; for a test. */
    EventsDispatcher events() {
        return events;
    }

    /** The watch over the feed's threads; for a test. */
    Watchdog watchdog() {
        return watchdog;
    }

    /** The feed's health; for a test. */
    HealthMonitor health() {
        return health;
    }

    /** What {@link #open} added and {@link #close} is to stop; for the health, and a test. */
    @Nullable
    OpenFeed running() {
        state.lock();
        try {
            return running;
        } finally {
            state.unlock();
        }
    }

    /** The event recovery of a request of the client's, over the feed as it is now. */
    private @Nullable Long recover(long producerId, URN eventId, boolean stateful) {
        @Nullable OpenFeed run;
        boolean wasClosed;
        state.lock();
        try {
            run = running;
            wasClosed = closed;
        } finally {
            state.unlock();
        }
        if (run == null || wasClosed) {
            LOG.warn(
                    "Recovery of {} from producer {} not accepted: the feed is {}",
                    eventId,
                    producerId,
                    wasClosed ? "closed" : "not open");
            return null;
        }
        return run.recoverEvent(producerId, eventId, stateful);
    }

    private @Nullable EventRecoveryStatus recoveryStatus(long requestId) {
        @Nullable OpenFeed run;
        state.lock();
        try {
            run = closed ? null : running;
        } finally {
            state.unlock();
        }
        return run == null ? null : run.recoveryStatus(requestId);
    }

    /** What {@link #getRecoveryManager} returns: one, whether the feed is open yet or not. */
    private final class FeedRecovery implements RecoveryManager {
        @Override
        public @Nullable Long initiateEventOddsMessagesRecovery(long producerId, URN eventId) {
            return recover(producerId, eventId, false);
        }

        @Override
        public @Nullable Long initiateEventStatefulMessagesRecovery(long producerId, URN eventId) {
            return recover(producerId, eventId, true);
        }

        @Override
        public @Nullable EventRecoveryStatus getEventRecoveryStatus(long requestId) {
            return recoveryStatus(requestId);
        }
    }

    private boolean isClosed() {
        state.lock();
        try {
            return closed;
        } finally {
            state.unlock();
        }
    }

    private static InitException closedBeforeStart() {
        return new InitException(INIT_FAILED + ": the feed was closed", null);
    }

    private static InitException closedBeforeOpen() {
        return new InitException("Failed to open the feed: the feed was closed", null);
    }

    /** Holds what 0.0.x's companion object had. */
    public static final class Companion {
        private Companion() {}

        public OddsFeedConfigurationBuilder getOddsFeedConfigurationBuilder() {
            return OddsFeed.getOddsFeedConfigurationBuilder();
        }
    }
}
