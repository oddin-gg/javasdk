package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.api.BookmakerDetail;
import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.internal.feed.FeedCore;
import com.oddin.oddsfeedsdk.internal.feed.OpenFeed;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.session.SessionRegistry;
import com.oddin.oddsfeedsdk.internal.session.SessionSpec;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
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
 * once. When it fails the call throws an {@link InitException} saying "Failed to init odds feed",
 * with the reason as its cause, and the next call tries again. The managers work before the feed
 * opens; {@link #close} releases what the start built.
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
     * Before the feed opens there is no queue a recovery's messages could reach, so no request is
     * accepted.
     */
    private static final RecoveryManager NOT_OPEN = new RecoveryManager() {
        @Override
        public @Nullable Long initiateEventOddsMessagesRecovery(long producerId, URN eventId) {
            return notAccepted(producerId, eventId);
        }

        @Override
        public @Nullable Long initiateEventStatefulMessagesRecovery(long producerId, URN eventId) {
            return notAccepted(producerId, eventId);
        }

        private @Nullable Long notAccepted(long producerId, URN eventId) {
            LOG.warn("Recovery of {} from producer {} not accepted: the feed is not open", eventId, producerId);
            return null;
        }
    };

    /**
     * Keeps {@code OddsFeed.Companion.getOddsFeedConfigurationBuilder()} compiling: 0.0.x was
     * Kotlin, and that is how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    private final GlobalEventsListener listener;
    private final OddsFeedConfiguration configuration;
    private final @Nullable OddsFeedExtListener extListener;
    private final SessionRegistry sessions;

    /** Held by the one start under way, so callers that come meanwhile wait for it, not start again. */
    private final ReentrantLock starting = new ReentrantLock();

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
        this.listener = requireNonNull(listener, "listener");
        this.configuration = requireNonNull(configuration, "configuration");
        this.extListener = null;
        this.sessions = new SessionRegistry(null);
    }

    public OddsFeed(
            GlobalEventsListener listener, OddsFeedConfiguration configuration, OddsFeedExtListener extListener) {
        this.listener = requireNonNull(listener, "listener");
        this.configuration = requireNonNull(configuration, "configuration");
        this.extListener = requireNonNull(extListener, "extListener");
        this.sessions = new SessionRegistry(extListener);
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
     * Event recoveries. Until the feed opens, a request is not accepted: it returns null, since no
     * session's queue exists yet that the recovered messages could reach.
     */
    public RecoveryManager getRecoveryManager() {
        core();
        return NOT_OPEN;
    }

    /**
     * Opens the feed for the sessions built so far: starts the feed if it has not started, checks
     * the sessions' interests combine, disables the producers no session asks for, then connects to
     * the broker and starts delivering. All or nothing: when a step fails, what it started is
     * closed again, nothing is left running or connected, and this throws; the managers stay, until
     * {@link #close}. One-shot, as in 0.0.x: once the sessions are taken, a second call throws,
     * whatever came of the first; the client closes the feed and makes a new one.
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
        var plan = Sessions.plan(specs, producers.getAvailableProducers(), configuration.getSdkNodeId());
        plan.disabledProducers().forEach(id -> producers.setProducerState(id, false));
        var run = OpenFeed.build(core, plan, configuration);
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
        run.start();
        LOG.info("Odds feed opened with {} session(s)", specs.size());
    }

    /**
     * Stops delivering and releases everything the feed has: the broker connection, the sessions'
     * threads once their callbacks return, and what the start built. Ends a start or an open under
     * way, which then fails. Waits for the feed's threads within one shutdown timeout, five seconds,
     * for all of them together; a callback still running then is left to end on its own and said so
     * in the log. From one of the feed's own callbacks it does not wait for that callback. Closing
     * twice does nothing more, and closing a feed whose start failed has nothing to release. Once
     * closed, the feed does not start or open again; the managers it had already handed out stay,
     * closed.
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
        var deadline = System.nanoTime() + OpenFeed.SHUTDOWN_TIMEOUT.toNanos();
        if (calling != null) {
            calling.close();
        }
        // every thread is told before any is waited for, so they stop together, within one deadline
        if (run != null) {
            run.stop();
        }
        if (built != null) {
            // the REST client first, so a callback waiting on a call returns at once; the events
            // dispatcher last, which waits for its own thread
            built.close();
        }
        if (run != null) {
            run.awaitStop(deadline);
        }
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
            try {
                built = FeedCore.start(configuration, listener, extListener, this::startingWith);
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
