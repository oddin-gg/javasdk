package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.message.Routes;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSnapshotComplete;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A session's dispatcher: one thread that takes the session's deliveries in the order they came and
 * takes each through every step to its acknowledgement.
 *
 * <ol>
 *   <li><b>Decode.</b> A body over the maximum message size, or one that does not decode, reaches
 *       {@code onUnparsableMessage}, for the match its routing key names.
 *   <li><b>Raw callbacks.</b> The extended listener, if any, gets every message decoded, alives and
 *       snapshot completes included, as 0.0.x gave them.
 *   <li><b>Filter.</b> A message of a producer the producer list does not have is dropped (KD-3), and
 *       so is one of a producer the client disabled or that the session's interest is not for, as in
 *       0.0.x. Alives and snapshot completes go to the recovery actor and no further. A fixture change
 *       another session, or this one, has delivered already is dropped (see {@link FixtureChanges}).
 *   <li><b>Cache write.</b> An odds change's match status is written to the match's live state, unless
 *       an older message than one already written (KD-13) or older than the match status age by the
 *       producer's own clock (KD-14); a fixture change invalidates the match and its fixture, or the
 *       tournament. Before the callback, so the callback reads what the message carried.
 *   <li><b>Build</b> the message the client gets.
 *   <li><b>Callback</b> on the session's listener.
 *   <li><b>Facts.</b> The recovery actor hears the session has finished the message, with when it was
 *       taken: it moves the session's checkpoint and is the safety net's sample.
 *   <li><b>Acknowledgement,</b> whatever came of the steps before.
 * </ol>
 *
 * <p>One failure policy for every step: what a step throws, even an error, is caught, counted,
 * logged and reported to the global listener's {@code onCallbackFailure} - flagged as the client's
 * code for a callback, as the SDK's for the others - and the message is acknowledged; nothing is
 * requeued, which would loop a poison message. A decode failure also reaches {@code
 * onUnparsableMessage}; a failed cache write or build reaches no message callback, since there is
 * no message to deliver. The thread goes on in every case, and an interrupt a callback leaves behind
 * is cleared.
 *
 * <p>A replay session posts no facts, as a replay feed runs no recovery, and writes the live state
 * of its old messages as current, in the order they come. A client's {@code SYSTEM_ALIVE_ONLY}
 * session carries no producer liveness and posts no facts either.
 *
 * <p>Safe for concurrent use; {@link #start} and {@link #close} are the only calls from outside.
 */
public final class SessionDispatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SessionDispatcher.class);

    /** How long the thread waits for a delivery before it looks whether it is closed. */
    private static final Duration POLL = Duration.ofSeconds(1);
    /** How long close() waits for a callback to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final OddsFeedSession session;
    private final MessageInterest interest;
    private final OddsFeedListener listener;
    private final @Nullable OddsFeedExtListener extListener;
    private final SessionTransport transport;
    private final @Nullable SessionFacts facts;
    private final boolean replay;
    private final Pipeline pipeline;

    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong unparsable = new AtomicLong();
    private final AtomicLong oversized = new AtomicLong();
    private final AtomicLong sdkFailures = new AtomicLong();
    private final AtomicLong callbackFailures = new AtomicLong();
    private final AtomicLong unknownProducers = new AtomicLong();
    private final AtomicLong repeatedFixtureChanges = new AtomicLong();

    private final Thread thread;
    private volatile boolean closed;
    /** When the delivery handled now was taken, epoch millis, 0 between deliveries; for the watchdog. */
    private volatile long busySince;

    /**
     * @param id the feed's number for the session, for its thread's name
     * @param session what the session's callbacks are given
     * @param extListener the client's extended listener, null for none
     * @param facts where the session's facts go, null for none: a replay session's, or one the
     *     recovery actor does not follow. The dispatcher posts neither {@link SessionFacts#channelLost}
     *     nor {@link SessionFacts#channelReopened}: the channel's loss and its replacement are the
     *     transport's to tell, through the session's {@code ChannelEvents}
     * @param replay whether it is a replay session, whose messages are old by design
     */
    public SessionDispatcher(
            int id,
            OddsFeedSession session,
            MessageInterest interest,
            OddsFeedListener listener,
            @Nullable OddsFeedExtListener extListener,
            SessionTransport transport,
            @Nullable SessionFacts facts,
            boolean replay,
            Pipeline pipeline) {
        this.session = session;
        this.interest = interest;
        this.listener = listener;
        this.extListener = extListener;
        this.transport = transport;
        this.facts = interest == MessageInterest.SYSTEM_ALIVE_ONLY || replay ? null : facts;
        this.replay = replay;
        this.pipeline = pipeline;
        this.thread =
                Thread.ofPlatform().daemon().name("oddsfeed-session-" + id).unstarted(this::run);
    }

    public void start() {
        thread.start();
    }

    /**
     * Stops taking deliveries, once the one being handled is done or after a wait; what the queue still
     * holds goes with the session's channel. From one of the session's callbacks it does not wait: the
     * thread ends once the callback returns and its message is acknowledged.
     */
    @Override
    public void close() {
        closed = true;
        if (Thread.currentThread().equals(thread)) {
            // a callback closing the session or the feed: waiting here would wait for itself
            return;
        }
        if (thread.isAlive()) {
            try {
                if (!thread.join(CLOSE_WAIT)) {
                    LOG.warn("The dispatcher of {} did not stop within {}: a callback still runs", thread, CLOSE_WAIT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ for the watchdog and getHealth()

    /** When the delivery being handled was taken, epoch millis by the SDK's clock; 0 when none is. */
    public long busySince() {
        return busySince;
    }

    /** Deliveries handled to their acknowledgement. */
    public long handled() {
        return handled.get();
    }

    /** Messages that did not decode, or were over the maximum message size. */
    public long unparsable() {
        return unparsable.get();
    }

    /** Messages over the maximum message size, which were not decoded. */
    public long oversized() {
        return oversized.get();
    }

    /** Steps of the SDK's own that failed: cache writes and builds, and decoding. */
    public long sdkFailures() {
        return sdkFailures.get();
    }

    /** Callbacks of the client's that threw. */
    public long callbackFailures() {
        return callbackFailures.get();
    }

    /** Messages dropped for a producer the producer list does not have. */
    public long unknownProducers() {
        return unknownProducers.get();
    }

    /** Fixture changes dropped as delivered already. */
    public long repeatedFixtureChanges() {
        return repeatedFixtureChanges.get();
    }

    // ------------------------------------------------------------------ the thread

    private void run() {
        while (!closed) {
            RawDelivery delivery;
            try {
                delivery = transport.queue().poll(POLL);
            } catch (InterruptedException e) {
                // nobody but close() ends the dispatcher, and close() sets closed first
                continue;
            }
            if (delivery != null) {
                handle(delivery);
            }
        }
    }

    /** One delivery, every step, then its acknowledgement; package-private for a test to drive. */
    void handle(RawDelivery delivery) {
        long takenAt = Math.max(1, pipeline.clock().millis());
        busySince = takenAt;
        try {
            process(delivery, takenAt);
        } catch (Throwable e) {
            // each step guards itself; this is the net under the net
            sdkFailed("dispatch", e);
        } finally {
            transport.ack(delivery);
            handled.incrementAndGet();
            busySince = 0;
            // an interrupt the client's code left would end the next wait at once
            Thread.interrupted();
        }
    }

    private void process(RawDelivery delivery, long takenAt) {
        RoutingKeyInfo route = Routes.parse(delivery.routingKey());
        byte[] body = delivery.body();
        if (body == null) {
            oversized.incrementAndGet();
            sdkFailed(
                    "decode",
                    new IllegalArgumentException("a message of " + delivery.size()
                            + " bytes, over the maximum message size, on " + route.getFullRoutingKey()));
            unparsable(route, null, delivery);
            return;
        }
        UnparsedMessage decoded;
        try {
            decoded = pipeline.decoder().decode(body);
        } catch (DecodeException e) {
            sdkFailed("decode", e);
            unparsable(route, body, delivery);
            return;
        }
        if (!(decoded instanceof BasicMessage message)) {
            sdkFailed(
                    "decode",
                    new IllegalArgumentException(
                            "not a feed message: " + decoded.getClass().getSimpleName()));
            unparsable(route, body, delivery);
            return;
        }
        raw(message, body, route, delivery);

        long producerId = message.getProduct();
        Producer producer = pipeline.producers().getProducer(producerId);
        if (producer == null) {
            long unknown = unknownProducers.incrementAndGet();
            if (unknown == 1 || unknown % 1_000 == 0) {
                LOG.warn(
                        "A message of producer {}, which the producer list does not have, is dropped; {} so far",
                        producerId,
                        unknown);
            }
            return;
        }
        if (!pipeline.producers().isProducerEnabled(producerId) || !interest.isProducerInScope(producer)) {
            return;
        }
        SessionFacts told = facts;
        switch (message) {
            case OFAlive alive -> {
                if (told != null) {
                    told.alive(producerId, alive.getTimestamp(), takenAt, alive.getSubscribed() == 1);
                }
                return;
            }
            case OFSnapshotComplete complete -> {
                if (told != null) {
                    told.snapshotComplete(producerId, complete.getRequestId());
                }
                return;
            }
            default -> {}
        }
        try {
            deliver(message, route, body, producer, timestamp(message.getTimestamp(), delivery), takenAt, delivery);
        } finally {
            if (told != null) {
                Long requestId = requestId(message);
                // a live message carries no request id
                told.processed(producerId, message.getTimestamp(), takenAt, requestId == null ? 0 : requestId);
            }
        }
    }

    private void deliver(
            BasicMessage message,
            RoutingKeyInfo route,
            byte[] body,
            Producer producer,
            MessageTimestamp timestamp,
            long takenAt,
            RawDelivery delivery) {
        if (message instanceof OFFixtureChange change) {
            URN id;
            try {
                id = URN.parse(change.getEventId());
            } catch (RuntimeException e) {
                // checked before it is remembered: an id that is no URN keeps nothing for an hour
                sdkFailed("cache write", e);
                return;
            }
            if (!pipeline.fixtureChanges().first(producer.getId(), id, change.getTimestamp())) {
                repeatedFixtureChanges.incrementAndGet();
                return;
            }
        }
        try {
            write(message, producer.getId(), takenAt, delivery.receivedAt());
        } catch (Throwable e) {
            sdkFailed("cache write", e);
            return;
        }
        EventMessage<SportEvent> built;
        try {
            SportEvent event = pipeline.messages().event(route);
            built = pipeline.messages().build(message, event, producer, body, timestamp);
        } catch (Throwable e) {
            sdkFailed("build", e);
            return;
        }
        if (built != null) {
            callback(built);
        }
    }

    /** What the message says of the caches, before the callback reads them. */
    private void write(BasicMessage message, long producerId, long takenAt, Instant receivedAt) {
        switch (message) {
            case OFOddsChange odds -> {
                OFSportEventStatus status = odds.getSportEventStatus();
                URN id = URN.parse(odds.getEventId());
                if (status != null && URN.TypeMatch.equals(id.getType())) {
                    if (replay) {
                        // old by design, and a run played again repeats the last one's timestamps
                        pipeline.matches().replayed(id, producerId, odds.getTimestamp(), receivedAt, status);
                    } else {
                        long age = pipeline.offsets().age(producerId, odds.getTimestamp(), takenAt);
                        pipeline.matches()
                                .oddsChange(
                                        id,
                                        producerId,
                                        odds.getTimestamp(),
                                        Duration.ofMillis(age),
                                        receivedAt,
                                        status);
                    }
                }
            }
            case OFFixtureChange change -> {
                URN id = URN.parse(change.getEventId());
                switch (id.getType()) {
                    case URN.TypeMatch -> pipeline.matches().fixtureChange(id);
                    case URN.TypeTournament -> pipeline.profiles().clearTournament(id);
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private void callback(EventMessage<SportEvent> message) {
        switch (message) {
            case OddsChange<SportEvent> odds -> client("onOddsChange", () -> listener.onOddsChange(session, odds));
            case BetStop<SportEvent> stop -> client("onBetStop", () -> listener.onBetStop(session, stop));
            case BetSettlement<SportEvent> settlement ->
                client("onBetSettlement", () -> listener.onBetSettlement(session, settlement));
            case RollbackBetSettlement<SportEvent> rollback ->
                client("onRollbackBetSettlement", () -> listener.onRollbackBetSettlement(session, rollback));
            case BetCancel<SportEvent> cancel -> client("onBetCancel", () -> listener.onBetCancel(session, cancel));
            case RollbackBetCancel<SportEvent> rollback ->
                client("onRollbackBetCancel", () -> listener.onRollbackBetCancel(session, rollback));
            case FixtureChange<SportEvent> change ->
                client("onFixtureChange", () -> listener.onFixtureChange(session, change));
            default -> sdkFailed("build", new IllegalStateException("no callback for " + message));
        }
    }

    private void raw(BasicMessage message, byte[] body, RoutingKeyInfo route, RawDelivery delivery) {
        OddsFeedExtListener ext = extListener;
        if (ext == null) {
            return;
        }
        MessageTimestamp received = timestamp(message.getTimestamp(), delivery);
        client("onRawFeedMessageReceived", () -> ext.onRawFeedMessageReceived(message, interest, route, received));
        MessageTimestamp bytes = timestamp(message.getTimestamp(), delivery);
        client("onRawFeedMessageBytes", () -> ext.onRawFeedMessageBytes(body, interest, route, bytes));
    }

    /** A message that could not be read, for the event its routing key names, if it names one. */
    private void unparsable(RoutingKeyInfo route, byte @Nullable [] body, RawDelivery delivery) {
        unparsable.incrementAndGet();
        UnparsableMessage<SportEvent> message;
        try {
            message = pipeline.messages().unparsable(route, body, timestamp(0, delivery));
        } catch (RuntimeException noEvent) {
            // as in 0.0.x: without an event to name there is nothing to deliver
            LOG.debug(
                    "An unparsable message on {} names no event, so it is not delivered",
                    route.getFullRoutingKey(),
                    noEvent);
            return;
        }
        client("onUnparsableMessage", () -> listener.onUnparsableMessage(session, message));
    }

    /**
     * When the message was made, sent, received and handed to the client, as 0.0.x gave them: a fresh
     * one for each callback that gets one, since the client may change it.
     */
    private MessageTimestamp timestamp(long created, RawDelivery delivery) {
        Instant sent = delivery.sentAt();
        return new MessageTimestamp(
                created,
                sent == null ? 0 : sent.toEpochMilli(),
                delivery.receivedAt().toEpochMilli(),
                pipeline.clock().millis());
    }

    private static @Nullable Long requestId(BasicMessage message) {
        return switch (message) {
            case OFOddsChange odds -> odds.getRequestId();
            case OFBetStop stop -> stop.getRequestId();
            case OFBetSettlement settlement -> settlement.getRequestId();
            case OFRollbackBetSettlement rollback -> rollback.getRequestId();
            case OFBetCancel cancel -> cancel.getRequestId();
            case OFRollbackBetCancel rollback -> rollback.getRequestId();
            case OFFixtureChange change -> change.getRequestId();
            default -> null;
        };
    }

    // ------------------------------------------------------------------ the failure policy

    /** Runs the client's callback; what it throws is the client's failure, and the session goes on. */
    private void client(String callback, Runnable call) {
        try {
            call.run();
        } catch (Throwable e) {
            long failures = callbackFailures.incrementAndGet();
            if (failures == 1 || failures % 1_000 == 0) {
                LOG.error(
                        "The client's {} threw; {} callbacks of the session have so far, it goes on",
                        callback,
                        failures,
                        e);
            } else {
                LOG.debug("The client's {} threw", callback, e);
            }
            pipeline.events().callbackFailed(callback, true, e, session);
        }
    }

    private void sdkFailed(String step, Throwable e) {
        long failures = sdkFailures.incrementAndGet();
        if (failures == 1 || failures % 1_000 == 0) {
            LOG.warn("The {} of a message failed; {} steps of the session have so far, it goes on", step, failures, e);
        } else {
            LOG.debug("The {} of a message failed", step, e);
        }
        pipeline.events().callbackFailed(step, false, e, session);
    }
}
