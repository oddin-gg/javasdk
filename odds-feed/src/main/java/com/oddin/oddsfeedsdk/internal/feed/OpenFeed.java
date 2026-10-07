package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpSettings;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpTransport;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.dispatch.AliveDispatcher;
import com.oddin.oddsfeedsdk.internal.dispatch.Pipeline;
import com.oddin.oddsfeedsdk.internal.dispatch.SessionDispatcher;
import com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor;
import com.oddin.oddsfeedsdk.internal.recovery.RecoverySettings;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code open()} adds to the feed: the broker connection, a dispatcher per session, and the
 * SDK's own alive dispatcher and the recovery actor - neither for a replay feed, which runs no
 * recovery, as in 0.0.x. Built without a thread or a connection, then started once; closed once, by
 * the feed or by a start that fails. The events dispatcher it reports to is the feed's, running
 * since the feed started, and stays when an open fails, with the managers.
 *
 * <p>The actor hears of the connection from the transport itself, before the events dispatcher
 * does, of the alives from the alive dispatcher, of each session's messages from its dispatcher, and
 * of each session's channel from the transport. It starts before the transport opens and asks for
 * nothing before the transport's first up, which comes once every session's queue is bound.
 */
public final class OpenFeed {

    private static final Logger LOG = LoggerFactory.getLogger(OpenFeed.class);

    /** The exchange of the live feed, as 0.0.x named it. */
    static final String FEED_EXCHANGE = "oddinfeed";

    /** The exchange a replay plays to, as 0.0.x named it. */
    static final String REPLAY_EXCHANGE = "oddinreplay";

    /**
     * How long the feed's close waits, for every thread together: a callback that runs past it is
     * left to end on its own, on a daemon thread, and said so in the log.
     */
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How much longer than the HTTP timeout an event recovery's caller waits for the API's answer:
     * the request's own deadline covers the call and its retries, and this the actor's turn around
     * it, as a loader's waiters wait for its fetch.
     */
    static final Duration ANSWER_MARGIN = Duration.ofSeconds(1);

    private enum State {
        BUILT,
        STARTED,
        CLOSED
    }

    private final AmqpTransport transport;
    private final List<SessionDispatcher> sessions;
    private final @Nullable AliveDispatcher alives;
    /** Null for a replay feed. */
    private final @Nullable RecoveryActor actor;
    /** Each session's facts, as the actor took them; none for a replay feed. */
    private final List<SessionFacts> facts;
    /** How long an event recovery's caller waits for the API's answer. */
    private final Duration answerWait;
    /** What the transport tells of the connection, in turn; for a test. */
    private final ConnectionTee connection;
    /** A test's hook, run in a start just before the transport opens. */
    volatile Runnable beforeTransportOpens = () -> {};

    /** Guards {@link #state}, briefly: never held while the transport opens or a thread is waited for. */
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.BUILT;

    private OpenFeed(
            AmqpTransport transport,
            List<SessionDispatcher> sessions,
            @Nullable AliveDispatcher alives,
            @Nullable RecoveryActor actor,
            List<SessionFacts> facts,
            Duration answerWait,
            ConnectionTee connection) {
        this.transport = transport;
        this.sessions = List.copyOf(sessions);
        this.alives = alives;
        this.actor = actor;
        this.facts = List.copyOf(facts);
        this.answerWait = answerWait;
        this.connection = connection;
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
        // a replay feed runs no recovery, so it needs no liveness of its own, as in 0.0.x
        var actor = replay
                ? null
                : new RecoveryActor(
                        core.producers(),
                        RecoverySettings.from(configuration),
                        core.api(),
                        new RecoveryTee(List.of(core.events())),
                        core.fetches());
        var alives = actor == null ? null : new AliveDispatcher(decoder, core.offsets(), actor);
        // the actor first, so a producer's state follows the connection before the client hears of it
        var connection = new ConnectionTee(actor == null ? List.of(core.events()) : List.of(actor, core.events()));
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
        var facts = new ArrayList<SessionFacts>();
        for (Sessions.Planned planned : plan.sessions()) {
            var spec = planned.spec();
            var channel = new LateChannelEvents();
            var session = transport.addSession(planned.routingKeys(), channel);
            @Nullable SessionFacts told = null;
            if (actor != null) {
                // the actor makes the session's side from its transport; bound before the transport opens
                told = actor.openSession(planned.info(), session);
                channel.bind(told);
                facts.add(told);
            }
            dispatchers.add(new SessionDispatcher(
                    spec.id(),
                    spec.session(),
                    spec.interest(),
                    spec.listener(),
                    spec.extListener(),
                    session,
                    told,
                    spec.replay(),
                    pipeline));
        }
        return new OpenFeed(
                transport,
                dispatchers,
                alives,
                actor,
                facts,
                configuration.getHttpClientTimeout().plus(ANSWER_MARGIN),
                connection);
    }

    /**
     * Starts the dispatchers and the recovery actor, then opens the transport: every queue is
     * consumed by the time a message can arrive, and the actor hears the transport's first up. All
     * or nothing: when any of it fails, or the feed closes meanwhile, everything started is closed
     * again before this throws.
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
            if (actor != null) {
                // before the transport tells it anything; a loss told before replaces no recovery point
                actor.start();
            }
        } finally {
            lock.unlock();
        }
        try {
            beforeTransportOpens.run();
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
     * The first half of closing: no delivery from now on, and every thread told to stop - the
     * recovery actor first that the feed is closing, then the sessions, which close one by one
     * without moving the resume points it publishes forward. Returns at once; {@link #awaitStop}
     * waits, then closes the actor and the connection. Closing again, or racing a start, does
     * nothing more.
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
        if (actor != null) {
            // before any session closes: closing them one by one must not move a resume point forward
            actor.closing();
        }
        if (was == State.STARTED) {
            if (alives != null) {
                alives.stop();
            }
            sessions.forEach(SessionDispatcher::stop);
        }
        facts.forEach(SessionFacts::closed);
        return true;
    }

    /**
     * The second half: waits for the dispatchers to end, then closes the recovery actor, which
     * handles the facts they posted, then the connection, all within {@code deadline}, by {@link
     * System#nanoTime}; a dispatcher called from a session's own callback is not waited for. The
     * connection closes last, so the actor, closing, hears of no loss its close makes; it cuts an
     * open under way short, too. The events dispatcher is the feed's to stop and wait for.
     *
     * @return whether every thread ended in time
     */
    public boolean awaitStop(long deadline) {
        var stopped = alives == null || alives.awaitStop(deadline);
        for (SessionDispatcher session : sessions) {
            stopped &= session.awaitStop(deadline);
        }
        if (actor != null) {
            stopped &= actor.close(deadline);
        }
        transport.close();
        return stopped;
    }

    /**
     * Asks for one event's messages again, and waits on the caller's thread for the API's answer,
     * for the HTTP timeout and a margin at most, as 0.0.x waited for its call.
     *
     * @return the request id, or null when the request was not accepted, was not answered in time,
     *     names a producer the list does not have, or the feed is a replay feed or closed
     */
    public @Nullable Long recoverEvent(long producerId, URN eventId, boolean stateful) {
        if (actor == null) {
            LOG.warn(
                    "Recovery of {} from producer {} not accepted: a replay feed runs no recovery",
                    eventId,
                    producerId);
            return null;
        }
        var reply = actor.recoverEvent(producerId, eventId, stateful);
        try {
            return reply.get(answerWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // the actor may still make the request; the caller has no id to look it up by
            LOG.warn("Recovery of {} from producer {} not answered within {}", eventId, producerId, answerWait);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            LOG.warn(
                    "Recovery of {} from producer {} not accepted: {}",
                    eventId,
                    producerId,
                    cause == null ? e.getMessage() : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Where the event recovery with this request id is; null for none, and on a replay feed. */
    public com.oddin.oddsfeedsdk.api.entities.@Nullable EventRecoveryStatus recoveryStatus(long requestId) {
        if (actor == null) {
            return null;
        }
        EventRecoveryStatus status = actor.recoveryStatus(requestId);
        return status == null ? null : status.toPublic();
    }

    /** Closes it all, within the shutdown timeout: for a start that fails, which keeps nothing of it. */
    void close() {
        var deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        stop();
        awaitStop(deadline);
    }

    /** The recovery actor, null for a replay feed; for a test. */
    @Nullable
    RecoveryActor actor() {
        return actor;
    }

    /** Who the transport tells of the connection, in the order it tells them; for a test. */
    List<ConnectionEvents> toldOfTheConnection() {
        return connection.told();
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
