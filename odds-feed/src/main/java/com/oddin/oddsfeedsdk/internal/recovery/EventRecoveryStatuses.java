package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event recoveries' statuses by request id, as the Go SDK keeps them: from the request until
 * five minutes after the recovery ended, then forgotten. The recovery actor's thread writes them;
 * any thread reads them, and sees each status whole.
 *
 * <p>Bounded: the pending ones are the event recoveries in flight, at most {@link
 * RecoverySettings#eventRecoveries()} per producer; of the ended ones it keeps {@link #ENDED_KEPT}
 * at most, and one more drops the one that ended first, before its five minutes, counted. The actor
 * forgets the ended ones on its ticks, and a read never shows one past its five minutes, so between
 * two ticks nothing is shown longer than it should be.
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

    private final Map<Long, EventRecoveryStatus> statuses = new ConcurrentHashMap<>();
    /** The ended statuses, in the order they ended; the actor's thread's only. */
    private final ArrayDeque<EventRecoveryStatus> ended = new ArrayDeque<>();

    private final InstantSource clock;
    private final RecoveryCounters counters;
    private final int endedKept;

    EventRecoveryStatuses(InstantSource clock, RecoveryCounters counters) {
        this(clock, counters, ENDED_KEPT);
    }

    /** With a bound a test sets. */
    EventRecoveryStatuses(InstantSource clock, RecoveryCounters counters, int endedKept) {
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
        EventRecoveryStatus status = statuses.get(requestId);
        if (status == null || expired(status, clock.instant())) {
            return null;
        }
        return status;
    }

    /** An event recovery asked for. The actor's thread only. */
    void pending(long requestId, long producerId, URN eventId, Instant at) {
        statuses.put(requestId, EventRecoveryStatus.pending(requestId, producerId, eventId, at));
    }

    /**
     * A pending recovery reached its end; one not pending - unknown, or ended already - stays as it
     * is. The actor's thread only.
     */
    void ended(long requestId, EventRecoveryStatus.State state, Instant at, @Nullable String reason) {
        EventRecoveryStatus status = statuses.get(requestId);
        if (status == null || status.state() != EventRecoveryStatus.State.PENDING) {
            return;
        }
        EventRecoveryStatus done = status.ended(state, at, reason);
        statuses.put(requestId, done);
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
    void expire(Instant now) {
        EventRecoveryStatus oldest;
        while ((oldest = ended.peekFirst()) != null && expired(oldest, now)) {
            forget(ended.removeFirst());
        }
    }

    /** How many statuses are kept, pending and ended; for a test. */
    int size() {
        return statuses.size();
    }

    /** Removes the status, unless its id has been asked for again since. */
    private void forget(EventRecoveryStatus status) {
        statuses.remove(status.requestId(), status);
    }

    private static boolean expired(EventRecoveryStatus status, Instant now) {
        Instant endedAt = status.endedAt();
        return endedAt != null && Duration.between(endedAt, now).compareTo(RETENTION) > 0;
    }
}
