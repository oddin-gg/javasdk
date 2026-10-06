package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.mq.MessageInterest;

/**
 * A session the builder made: the object the client keeps, and the one its listener's callbacks
 * receive, compared by identity. It has no methods, as in 0.0.x; what it receives is what its
 * {@link SessionSpec} says.
 */
public sealed class FeedSession implements OddsFeedSession permits ReplayFeedSession {

    private final int id;
    private final MessageInterest interest;

    FeedSession(int id, MessageInterest interest) {
        this.id = id;
        this.interest = interest;
    }

    /** The feed's own number for it: see {@link SessionSpec#id}. */
    public int id() {
        return id;
    }

    /** "session 2 (LIVE_ONLY)": enough for a client's log line to tell its sessions apart. */
    @Override
    public String toString() {
        return "session " + id + " (" + interest + ")";
    }
}
