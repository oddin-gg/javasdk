package com.oddin.oddsfeedsdk.internal.amqp;

/** What a session's dispatcher and the recovery side hold of a session's channel. */
public interface SessionTransport {

    /**
     * Acknowledges a delivery; one from a channel replaced since is skipped, since its channel is
     * gone and the broker has let it go with it.
     */
    void ack(RawDelivery delivery);

    /**
     * Replaces the session's channel, as a reconnect or the safety net does: cancels the consumer,
     * moves to a new epoch, takes the old channel's deliveries out of the queue, then declares a new
     * queue and starts a new consumer. What the broker held for the old queue is gone; recovery
     * covers it.
     */
    void reset();

    /**
     * Whether the session has an open channel to read from. After a {@link #reset} that could not
     * open the new channel, it is false until the transport has opened it again.
     */
    boolean isOpen();

    /** The channel the session reads now; each replacement adds one. */
    long epoch();

    /**
     * Acknowledgements skipped because their delivery's channel was gone, replaced or lost: the
     * broker let those deliveries go with it.
     */
    long skippedAcks();

    SessionQueue queue();
}
