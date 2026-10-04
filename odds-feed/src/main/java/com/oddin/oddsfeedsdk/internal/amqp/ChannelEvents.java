package com.oddin.oddsfeedsdk.internal.amqp;

/**
 * Where the transport reports what happens to one session's channel on a live connection: the
 * session's recovery side. A lost connection is the connection's event, and a {@link
 * SessionTransport#reset} the caller's own doing, so neither is told here. Each method runs on the
 * thread that saw the change, with the channel's lock held, so an implementation hands the event
 * over and returns.
 */
public interface ChannelEvents {

    /** Reports nothing. */
    ChannelEvents NONE = new ChannelEvents() {};

    /**
     * The broker closed or cancelled the session's channel on a live connection: its queue is gone,
     * and what the feed sends until a new queue is bound is lost. Told once per channel, before the
     * transport opens a new one, so before any delivery of the new channel.
     */
    default void lost() {}

    /**
     * A new channel replaced the one told lost, opened by the transport's reopen, a reconnect or a
     * reset: its queue is bound and consumed, so what the feed sends from now on reaches the
     * session. Told once per loss told.
     */
    default void reopened() {}
}
