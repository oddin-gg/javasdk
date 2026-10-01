package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.schema.utils.URN;

/**
 * Where the recovery actor reports what the client hears about: the feed's events dispatcher. Every
 * method runs on the actor's thread, so an implementation hands the event over and returns; it must
 * not block or throw.
 */
public interface RecoveryEvents {

    /** Reports nothing. */
    RecoveryEvents NONE = new RecoveryEvents() {};

    default void producerStatus(ProducerStatusChange change) {}

    /** Every session that receives the producer has seen the snapshot complete of an event recovery. */
    default void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {}

    /**
     * The safety net replaced a session's channel: what the session had not processed is dropped,
     * and the recovery asked for before covers it.
     *
     * @param ageMillis how old the session's live messages from the producer were
     */
    default void safetyNetReset(int session, long producerId, long ageMillis) {}

    /** The safety net asked for a recovery the API did not accept, so it reset nothing. */
    default void safetyNetRequestFailed(int session, long producerId, String reason) {}

    /**
     * A session has fallen behind with the safety net's resets spent, or has caught up again. A
     * lagging session is not a producer fault, so no producer goes down for it.
     */
    default void lagging(int session, boolean lagging) {}
}
