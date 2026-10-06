package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.amqp.ChannelEvents;

/**
 * A session's channel listener, bound once the session's recovery side exists: the transport takes
 * the listener when the session is added, and the recovery actor makes the session's side only from
 * the transport that adding returns. Bound before the transport opens, so no loss goes untold; until
 * then it tells nothing.
 */
final class LateChannelEvents implements ChannelEvents {

    private volatile ChannelEvents bound = ChannelEvents.NONE;

    /** From now on, tells {@code to}. */
    void bind(ChannelEvents to) {
        bound = to;
    }

    @Override
    public void lost() {
        bound.lost();
    }

    @Override
    public void reopened() {
        bound.reopened();
    }
}
