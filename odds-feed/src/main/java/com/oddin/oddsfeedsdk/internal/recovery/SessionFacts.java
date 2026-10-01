package com.oddin.oddsfeedsdk.internal.recovery;

/**
 * What a session's dispatcher tells the recovery actor, bound to its session. Each method hands the
 * fact over and returns at once, never waiting: when the actor's queue is full the fact is dropped
 * and counted.
 */
public interface SessionFacts {

    /**
     * The session has finished a message of the producer, its callback included.
     *
     * @param generatedAt the message's timestamp, epoch millis by the producer's clock
     * @param takenAt when the dispatcher took it from the session's queue, epoch millis by the SDK's
     *     clock, so its age includes the broker's backlog and the session's own
     * @param snapshot whether it belongs to a recovery, which it does when it carries a request id
     */
    void processed(long producerId, long generatedAt, long takenAt, boolean snapshot);

    /** The session has finished an alive from its own queue. */
    void alive(long producerId, long generatedAt, long takenAt, boolean subscribed);

    void snapshotComplete(long producerId, long requestId);

    /**
     * The session's channel was lost and opened again on a live connection, so its queue lost what
     * it held. A reconnect is reported by the connection, and the safety net's own resets need no
     * telling.
     */
    void channelLost();

    /** The session has closed. */
    void closed();
}
