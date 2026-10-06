package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.ReplaySession;
import com.oddin.oddsfeedsdk.mq.MessageInterest;

/**
 * A replay session: a {@link ReplaySession} for the builder to return, and an {@code
 * OddsFeedSession} for the callbacks to receive, as in 0.0.x.
 */
public final class ReplayFeedSession extends FeedSession implements ReplaySession {

    ReplayFeedSession(int id) {
        super(id, MessageInterest.ALL);
    }

    @Override
    public String toString() {
        return "replay " + super.toString();
    }
}
