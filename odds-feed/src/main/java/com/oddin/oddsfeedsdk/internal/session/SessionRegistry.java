package com.oddin.oddsfeedsdk.internal.session;

import com.oddin.oddsfeedsdk.OddsFeedSessionBuilder;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * One feed's sessions: those its builders registered, in the order they were built, until {@code
 * open()} takes them. From then on a builder's {@code build()} throws, where 0.0.x returned a
 * session that never received anything: the feed's queues are bound once, at {@code open()}.
 *
 * <p>Thread-safe; a builder itself is not, as in 0.0.x.
 */
public final class SessionRegistry {

    static final String OPEN = "the feed is already open; build sessions before open()";
    static final String NO_SESSIONS = "Feed created without sessions";

    private final @Nullable OddsFeedExtListener extListener;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<SessionSpec> sessions = new ArrayList<>();
    private boolean open;

    /** @param extListener the feed's extended listener, which every session gets */
    public SessionRegistry(@Nullable OddsFeedExtListener extListener) {
        this.extListener = extListener;
    }

    /** A new builder of this feed's sessions: what {@code OddsFeed.getSessionBuilder()} returns. */
    public OddsFeedSessionBuilder builder() {
        return new SessionBuilder(this);
    }

    /**
     * Takes the sessions for {@code open()}, which can take them once: from now on no session can
     * be built. A feed without sessions throws as 0.0.x's {@code open()} did, and stays as it was,
     * so a client can still build one and open it then, as on 0.0.x.
     *
     * @throws IllegalStateException without sessions, or when they were taken already
     */
    public List<SessionSpec> open() {
        lock.lock();
        try {
            if (open) {
                throw new IllegalStateException("the sessions were taken for open() already");
            }
            if (sessions.isEmpty()) {
                throw new IllegalStateException(NO_SESSIONS);
            }
            open = true;
            return List.copyOf(sessions);
        } finally {
            lock.unlock();
        }
    }

    /** Whether {@link #open} took the sessions. */
    public boolean isOpen() {
        lock.lock();
        try {
            return open;
        } finally {
            lock.unlock();
        }
    }

    /** Registers a session the builder checked, unless the feed is open already; makes it from its id. */
    <S extends FeedSession> S register(
            MessageInterest interest, Set<URN> events, OddsFeedListener listener, IntFunction<S> session) {
        lock.lock();
        try {
            if (open) {
                throw new IllegalStateException(OPEN);
            }
            var id = sessions.size() + 1;
            var made = session.apply(id);
            sessions.add(new SessionSpec(
                    id,
                    made,
                    interest,
                    Collections.unmodifiableSet(new LinkedHashSet<>(events)),
                    listener,
                    extListener));
            return made;
        } finally {
            lock.unlock();
        }
    }
}
