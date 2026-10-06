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
     * @param requestId the request id it carries, which makes it a recovery's snapshot message; 0
     *     for a live message, which carries none
     */
    void processed(long producerId, long generatedAt, long takenAt, long requestId);

    /** The session has finished an alive from its own queue. */
    void alive(long producerId, long generatedAt, long takenAt, boolean subscribed);

    void snapshotComplete(long producerId, long requestId);

    /**
     * The session's channel was lost on a live connection, so its queue lost what it held. Post it
     * when the loss is seen - where the transport learns the broker took the channel - and before the
     * new channel consumes: a message of the new channel handled first would move the session's
     * checkpoint past what the old queue dropped. A reconnect is reported by the connection, and the
     * safety net's own resets need no telling.
     */
    void channelLost();

    /**
     * A new channel replaced the one {@link #channelLost} told of, its queue bound: until this is
     * posted nothing is asked for the session's producers, since what the feed sent would reach no
     * queue. Post it for every loss posted, when the transport says so: {@code
     * ChannelEvents.reopened()}.
     */
    void channelReopened();

    /** The session has closed. */
    void closed();
}
