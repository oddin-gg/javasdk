package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpSettings;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpTransport;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.dispatch.AliveDispatcher;
import com.oddin.oddsfeedsdk.internal.dispatch.Pipeline;
import com.oddin.oddsfeedsdk.internal.dispatch.SessionDispatcher;
import com.oddin.oddsfeedsdk.internal.recovery.AliveFacts;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * What {@code open()} adds to the feed: the broker connection, a dispatcher per session, and the
 * SDK's own alive dispatcher - none for a replay feed. Built without a thread or a connection, then
 * started once; closed once, by the feed or by a start that fails. The events dispatcher it reports
 * to is the feed's, running since the feed started, and stays when an open fails, with the managers.
 *
 * <p>Producers are not followed yet: the alives reach no recovery, and the sessions post no facts,
 * so every producer stays as the producer list had it. The recovery actor joins where the comments
 * below say.
 */
public final class OpenFeed {

    /** The exchange of the live feed, as 0.0.x named it. */
    static final String FEED_EXCHANGE = "oddinfeed";

    /** The exchange a replay plays to, as 0.0.x named it. */
    static final String REPLAY_EXCHANGE = "oddinreplay";

    /**
     * How long the feed's close waits, for every thread together: a callback that runs past it is
     * left to end on its own, on a daemon thread, and said so in the log.
     */
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    /** Where the alives go until the recovery actor takes them: nowhere. */
    private static final AliveFacts NO_RECOVERY = (producer, generatedAt, receivedAt, subscribed) -> {};

    private enum State {
        BUILT,
        STARTED,
        CLOSED
    }

    private final AmqpTransport transport;
    private final List<SessionDispatcher> sessions;
    private final @Nullable AliveDispatcher alives;

    /** Guards {@link #state}, briefly: never held while the transport opens or a thread is waited for. */
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.BUILT;

    private OpenFeed(AmqpTransport transport, List<SessionDispatcher> sessions, @Nullable AliveDispatcher alives) {
        this.transport = transport;
        this.sessions = List.copyOf(sessions);
        this.alives = alives;
    }

    /**
     * Builds what the plan says over what the feed's start built: a transport on the feed's exchange,
     * or the replay one for a replay feed, with each session's queue added; nothing started.
     */
    public static OpenFeed build(FeedCore core, Sessions.Plan plan, OddsFeedConfiguration configuration) {
        var replay = plan.replay();
        var decoder = FeedDecoder.lenient(configuration.getMaxMessageSize());
        var pipeline = new Pipeline(
                decoder,
                core.messages(),
                core.matches(),
                core.profiles(),
                core.producers(),
                core.fixtureChanges(),
                core.offsets(),
                core.events(),
                InstantSource.system());
        // a replay feed runs no recovery, so it needs no liveness of its own, as in 0.0.x; the
        // recovery actor takes the alives here
        var alives = replay ? null : new AliveDispatcher(decoder, core.offsets(), NO_RECOVERY);
        // the recovery actor goes first here, the events dispatcher after it
        ConnectionEvents connection = new ConnectionTee(List.of(core.events()));
        var nodeId = configuration.getSdkNodeId();
        var transport = new AmqpTransport(
                AmqpSettings.of(
                        configuration,
                        core.bookmaker().virtualHost(),
                        core.bookmaker().connectionName(nodeId)),
                replay ? REPLAY_EXCHANGE : FEED_EXCHANGE,
                connection,
                alives);
        var dispatchers = new ArrayList<SessionDispatcher>();
        for (Sessions.Planned planned : plan.sessions()) {
            var spec = planned.spec();
            // bound to the session's facts once the recovery actor has them, before the transport opens
            var channel = new LateChannelEvents();
            var session = transport.addSession(planned.routingKeys(), channel);
            dispatchers.add(new SessionDispatcher(
                    spec.id(),
                    spec.session(),
                    spec.interest(),
                    spec.listener(),
                    spec.extListener(),
                    session,
                    null,
                    spec.replay(),
                    pipeline));
        }
        return new OpenFeed(transport, dispatchers, alives);
    }

    /**
     * Starts the dispatchers, then opens the transport: every queue is
     * consumed by the time a message can arrive. All or nothing: when any of it fails, or the feed
     * closes meanwhile, everything started is closed again before this throws.
     *
     * @throws InitException when the broker cannot be reached or refuses, or the feed closed as it
     *     opened
     */
    public void start() {
        lock.lock();
        try {
            if (state == State.STARTED) {
                throw new IllegalStateException("the feed opens once");
            }
            if (state == State.CLOSED) {
                throw closedAsItOpened(null);
            }
            state = State.STARTED;
            if (alives != null) {
                alives.start();
            }
            sessions.forEach(SessionDispatcher::start);
        } finally {
            lock.unlock();
        }
        // the recovery actor starts here, before the transport tells it anything
        try {
            transport.open();
        } catch (RuntimeException e) {
            boolean closedMeanwhile = isClosed();
            close();
            if (e instanceof InitException failed) {
                throw failed;
            }
            if (closedMeanwhile) {
                throw closedAsItOpened(e);
            }
            throw new InitException("Failed to open the feed", e);
        }
    }

    /**
     * The first half of closing: no delivery from now on, and every thread told to stop. Returns at
     * once; {@link #awaitStop} waits. Closing again, or racing a start, does nothing more.
     *
     * @return whether this call closed it, rather than an earlier one
     */
    public boolean stop() {
        State was;
        lock.lock();
        try {
            was = state;
            state = State.CLOSED;
        } finally {
            lock.unlock();
        }
        if (was == State.CLOSED) {
            return false;
        }
        // the recovery actor is told it is closing here, before any session closes: closing them one
        // by one must not move the resume point it publishes; it closes itself after them
        // cuts an open under way short, too
        transport.close();
        if (was == State.STARTED) {
            if (alives != null) {
                alives.stop();
            }
            sessions.forEach(SessionDispatcher::stop);
        }
        return true;
    }

    /**
     * The second half: waits for the dispatchers to end, all within {@code deadline}, by {@link
     * System#nanoTime}; one called from a session's own callback is not waited for. The events
     * dispatcher is the feed's to close, before this.
     *
     * @return whether every dispatcher ended in time
     */
    public boolean awaitStop(long deadline) {
        var stopped = alives == null || alives.awaitStop(deadline);
        for (SessionDispatcher session : sessions) {
            stopped &= session.awaitStop(deadline);
        }
        return stopped;
    }

    /** Closes it all, within the shutdown timeout: for a start that fails, which keeps nothing of it. */
    void close() {
        var deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        stop();
        awaitStop(deadline);
    }

    private boolean isClosed() {
        lock.lock();
        try {
            return state == State.CLOSED;
        } finally {
            lock.unlock();
        }
    }

    private static InitException closedAsItOpened(@Nullable Exception cause) {
        return new InitException("Failed to open the feed: the feed was closed as it opened", cause);
    }
}
