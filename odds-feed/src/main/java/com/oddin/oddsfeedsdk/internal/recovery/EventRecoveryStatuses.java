package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event recoveries' statuses by request id, as the Go SDK keeps them: from the request until
 * five minutes after the recovery ended, then forgotten. The recovery actor's thread writes them;
 * any thread reads them, and sees each status whole.
 *
 * <p>The five minutes are a duration between two local events, so they run on the feed clock's
 * monotonic side: a step of the wall clock neither forgets a status early nor keeps it longer. The
 * instants a status publishes, {@link EventRecoveryStatus#startedAt} and {@link
 * EventRecoveryStatus#endedAt}, are wall time, as the caller gave them.
 *
 * <p>Bounded: the pending ones are the event recoveries in flight, at most {@link
 * RecoverySettings#eventRecoveries()} per producer; of the ended ones it keeps {@link #ENDED_KEPT}
 * at most, and one more drops the one that ended first, before its five minutes, counted. The actor
 * forgets the ended ones on its ticks, and a read never shows one past its five minutes, so between
 * two ticks nothing is shown longer than it should be.
 *
 * <p>A feed whose close gave up waiting for the actor {@linkplain #abandon abandons} them: the
 * actor may not run again, so a pending one reads failed from then on, whenever the actor ends it.
 */
final class EventRecoveryStatuses {

    private static final Logger LOG = LoggerFactory.getLogger(EventRecoveryStatuses.class);

    /** How long an ended recovery's status is kept: the Go SDK's grace period. */
    static final Duration RETENTION = Duration.ofMinutes(5);

    /**
     * How many ended recoveries' statuses are kept at most: a few megabytes, and many more than a
     * client asks for in five minutes while the API keeps pace.
     */
    static final int ENDED_KEPT = 10_000;

    /** Why a recovery the close left pending failed. */
    static final String FEED_CLOSED = "the feed closed";

    /**
     * A status and the monotonic reading of when it ended, which retention counts from; unused while it is pending.
     */
    private record Kept(EventRecoveryStatus status, long endedMono) {}

    /** When the feed's close gave up waiting for the actor, on both sides of the clock. */
    private record Abandoned(Instant at, long mono) {}

    private final Map<Long, Kept> statuses = new ConcurrentHashMap<>();
    /** The ended statuses, in the order they ended; the actor's thread's only. */
    private final ArrayDeque<Kept> ended = new ArrayDeque<>();
    /** Orders {@link #abandon} and {@link #ended}: nothing ends well after it, or a read sees it undone. */
    private final ReentrantLock lock = new ReentrantLock();
    /** Null before the close gave up waiting for the actor. */
    private volatile @Nullable Abandoned abandoned;

    private final FeedClock clock;
    private final RecoveryCounters counters;
    private final int endedKept;

    EventRecoveryStatuses(FeedClock clock, RecoveryCounters counters) {
        this(clock, counters, ENDED_KEPT);
    }

    /** With a bound a test sets. */
    EventRecoveryStatuses(FeedClock clock, RecoveryCounters counters, int endedKept) {
        this.clock = clock;
        this.counters = counters;
        this.endedKept = endedKept;
    }

    /**
     * The recovery's status, or null for an id never asked for, or one that ended more than five
     * minutes ago. Safe from any thread.
     */
    @Nullable
    EventRecoveryStatus get(long requestId) {
        Kept kept = statuses.get(requestId);
        if (kept == null) {
            return null;
        }
        var status = kept.status();
        var endedMono = kept.endedMono();
        var gone = abandoned;
        if (gone != null && status.state() == EventRecoveryStatus.State.PENDING) {
            status = status.ended(EventRecoveryStatus.State.FAILED, gone.at(), FEED_CLOSED);
            endedMono = gone.mono();
        }
        return expired(status, endedMono, clock.monoMillis()) ? null : status;
    }

    /**
     * The feed's close gave up waiting for the actor, which may never end the pending recoveries
     * itself: they read as failed from now on, and any it ends later as failed too. Safe from any
     * thread, and once only; a status that ended before stays as it is.
     */
    void abandon() {
        lock.lock();
        try {
            if (abandoned == null) {
                abandoned = new Abandoned(clock.wall().instant(), clock.monoMillis());
            }
        } finally {
            lock.unlock();
        }
    }

    /** An event recovery asked for. The actor's thread only. */
    void pending(long requestId, long producerId, URN eventId, Instant at) {
        statuses.put(requestId, new Kept(EventRecoveryStatus.pending(requestId, producerId, eventId, at), 0));
    }

    /**
     * A pending recovery reached its end, at {@code at} by the wall clock, which is what it
     * publishes, and {@code mono} by the monotonic one, which its retention counts from; one not
     * pending - unknown, or ended already - stays as it is. The actor's thread only.
     */
    void ended(long requestId, EventRecoveryStatus.State state, Instant at, @Nullable String reason, long mono) {
        Kept kept = statuses.get(requestId);
        if (kept == null || kept.status().state() != EventRecoveryStatus.State.PENDING) {
            return;
        }
        Kept done;
        lock.lock();
        try {
            var gone = abandoned;
            // what a read has shown since the close gave up is what it stays
            done = gone == null
                    ? new Kept(kept.status().ended(state, at, reason), mono)
                    : new Kept(
                            kept.status().ended(EventRecoveryStatus.State.FAILED, gone.at(), FEED_CLOSED), gone.mono());
            statuses.put(requestId, done);
        } finally {
            lock.unlock();
        }
        ended.addLast(done);
        if (ended.size() > endedKept) {
            forget(ended.removeFirst());
            long dropped = counters.eventStatusesDropped.incrementAndGet();
            if (dropped == 1 || dropped % 1_000 == 0) {
                LOG.warn(
                        "More than {} event recoveries ended within {}: the oldest status is forgotten early, {}"
                                + " so far",
                        endedKept,
                        RETENTION,
                        dropped);
            }
        }
    }

    /** Forgets the statuses that ended more than five minutes ago. The actor's thread only. */
    void expire(long mono) {
        Kept oldest;
        while ((oldest = ended.peekFirst()) != null && expired(oldest.status(), oldest.endedMono(), mono)) {
            forget(ended.removeFirst());
        }
    }

    /** How many statuses are kept, pending and ended; for a test. */
    int size() {
        return statuses.size();
    }

    /** Removes the status, unless its id has been asked for again since. */
    private void forget(Kept kept) {
        statuses.remove(kept.status().requestId(), kept);
    }

    private static boolean expired(EventRecoveryStatus status, long endedMono, long mono) {
        return status.endedAt() != null && mono - endedMono > RETENTION.toMillis();
    }
}
