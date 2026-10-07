package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import org.jspecify.annotations.Nullable;

/**
 * Events of the feed as a whole, not of one session.
 *
 * <p>Every method runs on the feed's one events thread, never on a thread that handles messages, the
 * broker connection or recovery, so a slow callback here holds up neither the feed nor its sessions -
 * but it does hold up the events after it: an entity getter that loads from the API inside {@link
 * #onProducerStatusChange} delays the next status change by as long as the load takes. Events are
 * queued while a callback runs, up to a bound, and counted when one has to be dropped. Of a
 * producer's status and of the connection's state, the newest replaces one still queued, so a
 * client that falls behind hears the state as it is now rather than one long gone.
 *
 * <p>A callback that throws is logged and reported to {@link #onCallbackFailure}; the events after
 * it are delivered as usual.
 */
public interface GlobalEventsListener {
    void onProducerStatusChange(ProducerStatus producerStatus);

    /**
     * The broker connection was lost; the feed reconnects on its own. Not called when the feed itself
     * closes the connection. When several changes of the connection are queued behind a slow
     * callback, this is still called once for the loss among them. It says nothing yet of the
     * producers: as in 0.0.x, they may read as up while it runs; a producer the loss takes down is
     * told through {@link #onProducerStatusChange}.
     */
    void onConnectionDown();

    void onEventRecoveryCompleted(URN eventId, long requestId);

    /**
     * The broker connection changed state: connecting, up, down or about to try again. Called after
     * {@link #onConnectionDown} for a loss. New in 1.0; does nothing unless overridden.
     */
    default void onConnectionStateChange(ConnectionStateChange change) {}

    /**
     * Something no retry can fix: the API refused the access token, or the broker has refused the
     * login or the virtual host for long enough. The feed stops what it cannot do; the client's way
     * out is to close it and make a new one with what was wrong put right. New in 1.0; does nothing
     * unless overridden.
     *
     * @param cause what was caught, when there was something
     */
    default void onFatalError(String reason, @Nullable Throwable cause) {}

    /**
     * Each HTTP attempt of every call the SDK makes to the API, whatever came of it. New in 1.0; does
     * nothing unless overridden. These are telemetry: when they pile up behind a slow callback, the
     * oldest are dropped first.
     */
    default void onApiCall(ApiCallEvent call) {}

    /**
     * A callback of the client's threw, or a step of the SDK's own handling of a message failed; the
     * SDK went on. New in 1.0; does nothing unless overridden, the failure is logged either way. One
     * of these that throws is logged and not reported again.
     */
    default void onCallbackFailure(CallbackFailure failure) {}

    /**
     * A producer's status changed, its cause included: called after {@link #onProducerStatusChange}
     * for each change that one hears, and also when only the cause changed, as when an alive says a
     * producer still down is unsubscribed. New in 1.0; does nothing unless overridden. Of a producer's
     * changes, the newest replaces one still queued.
     */
    default void onProducerCauseChange(ProducerCauseChange change) {}

    /**
     * The safety net reset a session too far behind, or could not, since the API did not accept a
     * recovery it asked for first. New in 1.0; does nothing unless overridden.
     */
    default void onSafetyNetEvent(SafetyNetEvent event) {}

    /**
     * A session fell behind with the safety net's resets spent, or caught up again. New in 1.0; does
     * nothing unless overridden. Of a session's changes, the newest replaces one still queued.
     */
    default void onSessionLagChange(SessionLagChange change) {}

    /**
     * A part of the feed changed its health, as the SDK's own watch found. New in 1.0; does nothing
     * unless overridden. Each change is logged as well: a callback of this listener's that holds its
     * thread up is told there first, since the event queues behind it.
     */
    default void onHealthEvent(HealthEvent event) {}
}
