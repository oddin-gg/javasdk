package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.ReplaySession;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A session as its builder registered it, for {@code open()} to plan and start.
 *
 * @param id the feed's own number for it, from 1 in the order the sessions were built
 * @param interest {@code ALL} for a replay session, as in 0.0.x
 * @param events the events of a specified-matches session, in the order they were given; kept but
 *     ignored for any other interest, as 0.0.x did
 * @param extListener the feed's extended listener, which every session hands its raw messages
 */
public record SessionSpec(
        int id,
        FeedSession session,
        MessageInterest interest,
        Set<URN> events,
        OddsFeedListener listener,
        @Nullable OddsFeedExtListener extListener) {

    /** Whether it is the replay session, which is then the feed's only one. */
    public boolean replay() {
        return session instanceof ReplaySession;
    }
}
