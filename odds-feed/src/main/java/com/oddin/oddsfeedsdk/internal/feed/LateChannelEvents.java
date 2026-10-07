package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.amqp.ChannelEvents;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import org.jspecify.annotations.Nullable;

/**
 * A session's channel listener, bound once the session's recovery side exists: the transport takes
 * the listener when the session is added, and the recovery actor makes the session's side only from
 * the transport that adding returns. Bound before the transport opens, so no loss goes untold; until
 * then, and for a session the actor does not follow, it tells nothing.
 */
final class LateChannelEvents implements ChannelEvents {

    private volatile @Nullable SessionFacts bound;

    /** From now on, tells the session's facts of its channel's losses and replacements. */
    void bind(SessionFacts to) {
        bound = to;
    }

    @Override
    public void lost() {
        SessionFacts facts = bound;
        if (facts != null) {
            facts.channelLost();
        }
    }

    @Override
    public void reopened() {
        SessionFacts facts = bound;
        if (facts != null) {
            facts.channelReopened();
        }
    }
}
