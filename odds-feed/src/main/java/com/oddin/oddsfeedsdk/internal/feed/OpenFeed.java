package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.Joins;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpSettings;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpTransport;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.dispatch.AliveDispatcher;
import com.oddin.oddsfeedsdk.internal.dispatch.MessagePreload;
import com.oddin.oddsfeedsdk.internal.dispatch.Pipeline;
import com.oddin.oddsfeedsdk.internal.dispatch.SessionDispatcher;
import com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.internal.recovery.RecoverySettings;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code open()} adds to the feed: the broker connection, a dispatcher per session, and the
 * SDK's own alive dispatcher and the recovery actor - neither for a replay feed, which runs no
 * recovery, as in 0.0.x. Built without a thread or a connection, then started once; closed once, by
 * the feed or by a start that fails. The events dispatcher it reports to is the feed's, running
 * since the feed started, and stays when an open fails, with the managers. From the start to the
 * close it keeps one non-daemon thread, so the JVM does not end while the feed is open.
 *
 * <p>The actor hears of the connection from the transport itself, before the events dispatcher
 * does, of the alives from the alive dispatcher, of each session's messages from its dispatcher, and
 * of each session's channel from the transport. It starts before the transport opens and asks for
 * nothing before the transport's first up, which comes once every session's queue is bound.
 */
public final class OpenFeed {

    private static final Logger LOG = LoggerFactory.getLogger(OpenFeed.class);

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
    /**
     * How long a close waits, for every thread together: a callback that runs past it is left to end
     * on its own, on a daemon thread, and said so in the log.
     */
    private final Duration shutdownTimeout;
    /** What the transport tells of the connection, in turn; for a test. */
    private final ConnectionTee connection;
    /** What the recovery actor tells of its events, in turn; null for a replay feed; for a test. */
    private final @Nullable RecoveryTee recoveryTee;
    /** Ends {@link #keepAlive}: counted down once the close has waited for the rest. */
    private final CountDownLatch released = new CountDownLatch(1);
    /**
     * The feed's one non-daemon thread, from the start to the close, as 0.0.x's executors were: every
     * other thread of the feed is a daemon or a virtual thread, and the broker client's own, which is
     * not, is gone while the connection is down, so a client whose {@code main} returned after {@code
     * open()} would otherwise end during an outage.
     */
    private final Thread keepAlive =
            Thread.ofPlatform().daemon(false).name("oddsfeed-keep-alive").unstarted(this::keepAlive);
    /** A test's hook, run in a start just before the transport opens. */
    volatile Runnable beforeTransportOpens = () -> {};
    /** A test's hook, run in a start once the transport has opened. */
    volatile Runnable afterTransportOpens = () -> {};

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
            Duration shutdownTimeout,
            ConnectionTee connection,
            @Nullable RecoveryTee recoveryTee) {
        this.transport = transport;
        this.sessions = List.copyOf(sessions);
        this.alives = alives;
        this.actor = actor;
        this.facts = List.copyOf(facts);
        this.answerWait = answerWait;
        this.shutdownTimeout = shutdownTimeout;
        this.connection = connection;
        this.recoveryTee = recoveryTee;
    }

    /** With a health of its own; for a test. */
    static OpenFeed build(FeedCore core, Sessions.Plan plan, OddsFeedConfiguration configuration) {
        return build(core, plan, configuration, new HealthMonitor(core.events(), id -> null));
    }

    /**
     * Builds what the plan says over what the feed's start built: a transport on the configured
     * exchange, or the replay one for a replay feed, with each session's queue added; nothing started.
     *
     * @param health the feed's health, told of the recovery's events and the connection's after the
     *     events dispatcher: it keeps the sessions' lagging and the connection's time down
     */
    public static OpenFeed build(
            FeedCore core, Sessions.Plan plan, OddsFeedConfiguration configuration, HealthMonitor health) {
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
        // the client hears of a session lagging before the health tells of it
        var recoveryEvents = replay ? null : new RecoveryTee(List.of(core.events(), health));
        var actor = recoveryEvents == null
                ? null
                : new RecoveryActor(
                        core.producers(),
                        RecoverySettings.from(configuration),
                        core.api(),
                        recoveryEvents,
                        core.fetches());
        var alives = actor == null ? null : new AliveDispatcher(decoder, core.offsets(), actor);
        // the actor first, so a producer's state follows the connection before the client hears of it;
        // the client hears of the connection before the health tells of it
        var connection = new ConnectionTee(
                actor == null ? List.of(core.events(), health) : List.of(actor, core.events(), health));
        var nodeId = configuration.getSdkNodeId();
        var transport = new AmqpTransport(
                AmqpSettings.of(
                        configuration,
                        core.bookmaker().virtualHost(),
                        core.bookmaker().connectionName(nodeId)),
                replay ? configuration.getReplayExchangeName() : configuration.getExchangeName(),
                connection,
                alives);
        // the eager preload hears of each delivery a session's queue takes, and only queues a load
        Consumer<String> queued = configuration.isEagerEntityPreload()
                ? MessagePreload.of(core.matches()::preload, configuration)
                : routingKey -> {};
        var dispatchers = new ArrayList<SessionDispatcher>();
        var facts = new ArrayList<SessionFacts>();
        for (Sessions.Planned planned : plan.sessions()) {
            var spec = planned.spec();
            var channel = new LateChannelEvents();
            var session = transport.addSession(planned.routingKeys(), channel, queued);
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
                configuration.getShutdownTimeout(),
                connection,
                recoveryEvents);
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
            keepAlive.start();
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
        afterTransportOpens.run();
        if (isClosed()) {
            // a close came as the transport opened, and stopped what started: no open to report
            close();
            throw closedAsItOpened(null);
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
     * open under way short, too. The feed's non-daemon thread ends last. The events dispatcher is the
     * feed's to stop and wait for.
     *
     * @return whether every thread ended in time
     */
    public boolean awaitStop(long deadline) {
        try {
            var stopped = alives == null || alives.awaitStop(deadline);
            for (SessionDispatcher session : sessions) {
                stopped &= session.awaitStop(deadline);
            }
            if (actor != null) {
                stopped &= actor.close(deadline);
            }
            transport.close();
            return stopped;
        } finally {
            // last, so the JVM stays up until the connection has closed, and whatever came before
            // failed; it ends at once
            released.countDown();
            if (keepAlive.isAlive()) {
                Joins.uninterruptibly(keepAlive, deadline);
            }
        }
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
        return answer(actor.recoverEvent(producerId, eventId, stateful), answerWait, producerId, eventId);
    }

    /**
     * The actor's answer, waited for up to {@code wait}. A wait that ends answers the reply with null
     * for the actor - one still waiting for a session's channel, or still queued, is then never sent
     * with an id this caller never got - unless the actor answered first, in the same instant: the
     * caller then has what it answered, the id the request went out with.
     */
    static @Nullable Long answer(CompletableFuture<@Nullable Long> reply, Duration wait, long producerId, URN eventId) {
        try {
            try {
                return reply.get(wait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (reply.complete(null)) {
                    LOG.warn("Recovery of {} from producer {} not answered within {}", eventId, producerId, wait);
                    return null;
                }
                // the actor answered as the wait ended: complete(null) found the reply done
                return reply.getNow(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return reply.complete(null) ? null : reply.getNow(null);
            }
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause();
            LOG.warn(
                    "Recovery of {} from producer {} not accepted: {}",
                    eventId,
                    producerId,
                    cause == null ? e.getMessage() : cause.getMessage());
            return null;
        }
    }

    /** Where the event recovery with this request id is; null for none, and on a replay feed. */
    public com.oddin.oddsfeedsdk.api.entities.@Nullable EventRecoveryStatus recoveryStatus(long requestId) {
        if (actor == null) {
            return null;
        }
        EventRecoveryStatus status = actor.recoveryStatus(requestId);
        return status == null ? null : status.toPublic();
    }

    /** Until the close: only that ends it, not an interrupt, so the JVM stays up while the feed is open. */
    private void keepAlive() {
        var ended = false;
        while (!ended) {
            try {
                released.await();
                ended = true;
            } catch (InterruptedException e) {
                // the close, not an interrupt, ends it
            }
        }
    }

    /** Closes it all, within the shutdown timeout: for a start that fails, which keeps nothing of it. */
    void close() {
        var deadline = System.nanoTime() + shutdownTimeout.toNanos();
        stop();
        awaitStop(deadline);
    }

    /** The recovery actor, null for a replay feed; for a test. */
    @Nullable
    RecoveryActor actor() {
        return actor;
    }

    /** The broker connection; for the health. */
    AmqpTransport transport() {
        return transport;
    }

    /** The SDK's alive dispatcher, null for a replay feed; for the health. */
    @Nullable
    AliveDispatcher alives() {
        return alives;
    }

    /** A dispatcher per session, in the order the sessions were built; for the health. */
    List<SessionDispatcher> sessions() {
        return sessions;
    }

    /** Who the recovery actor tells of its events, in order; empty for a replay feed; for a test. */
    List<RecoveryEvents> toldOfTheRecovery() {
        return recoveryTee == null ? List.of() : recoveryTee.told();
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
