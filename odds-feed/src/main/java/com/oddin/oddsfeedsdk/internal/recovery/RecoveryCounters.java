package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the recovery actor counts, for {@code getHealth()}. Written by the actor and its workers,
 * read by anyone.
 */
public final class RecoveryCounters {

    final AtomicLong requested = new AtomicLong();
    final AtomicLong reissued = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    final AtomicLong timedOut = new AtomicLong();
    final AtomicLong abandoned = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong unknownCompletions = new AtomicLong();
    final AtomicLong unknownProducers = new AtomicLong();
    final AtomicLong eventRequested = new AtomicLong();
    final AtomicLong eventRefused = new AtomicLong();
    final AtomicLong eventExpired = new AtomicLong();
    final AtomicLong eventAbandoned = new AtomicLong();
    final AtomicLong eventCallerGone = new AtomicLong();
    final AtomicLong eventStatusesDropped = new AtomicLong();
    final AtomicLong resets = new AtomicLong();
    final AtomicLong resetDropped = new AtomicLong();
    final AtomicLong resetRequestsFailed = new AtomicLong();
    final AtomicLong factsDropped = new AtomicLong();
    final AtomicLong factsFailed = new AtomicLong();

    /** Producer recoveries asked for, re-issues included. */
    public long requested() {
        return requested.get();
    }

    /** Producer recoveries asked for again after one failed or timed out. */
    public long reissued() {
        return reissued.get();
    }

    /** Producer recoveries the API did not accept, or that timed out. */
    public long failed() {
        return failed.get();
    }

    /** Producer recoveries with no snapshot complete within the maximum recovery time. */
    public long timedOut() {
        return timedOut.get();
    }

    /** Producer recoveries given up because what they would send was lost with a queue. */
    public long abandoned() {
        return abandoned.get();
    }

    public long completed() {
        return completed.get();
    }

    /** Snapshot completes for no recovery in flight: another instance's, or one given up. */
    public long unknownCompletions() {
        return unknownCompletions.get();
    }

    /** Facts about a producer the producer list does not have. */
    public long unknownProducers() {
        return unknownProducers.get();
    }

    public long eventRequested() {
        return eventRequested.get();
    }

    /** Event recoveries the API did not accept, or that too many in flight turned away. */
    public long eventRefused() {
        return eventRefused.get();
    }

    /** Event recoveries with no snapshot complete within the maximum recovery time. */
    public long eventExpired() {
        return eventExpired.get();
    }

    /** Event recoveries given up because their snapshot complete went with a lost queue. */
    public long eventAbandoned() {
        return eventAbandoned.get();
    }

    /**
     * Event recoveries not asked for because their caller had stopped waiting for the answer, while
     * they waited for a session's channel or in the actor's queue.
     */
    public long eventCallerGone() {
        return eventCallerGone.get();
    }

    /**
     * Ended event recoveries whose status was forgotten before its five minutes, to keep at most
     * {@link EventRecoveryStatuses#ENDED_KEPT}.
     */
    public long eventStatusesDropped() {
        return eventStatusesDropped.get();
    }

    /** Channels the safety net replaced. */
    public long resets() {
        return resets.get();
    }

    /** Messages in a session's queue when the safety net replaced its channel. */
    public long resetDropped() {
        return resetDropped.get();
    }

    /** Recoveries the safety net asked for that the API did not accept, so nothing was reset. */
    public long resetRequestsFailed() {
        return resetRequestsFailed.get();
    }

    /** Facts the actor's queues had no room for. */
    public long factsDropped() {
        return factsDropped.get();
    }

    /** Facts the actor failed on; it goes on with the next. */
    public long factsFailed() {
        return factsFailed.get();
    }

    /** What it has counted so far, as {@code getHealth()} shows it; each counter read on its own. */
    public FeedHealth.Recovery snapshot() {
        return new FeedHealth.Recovery(
                requested.get(),
                reissued.get(),
                failed.get(),
                timedOut.get(),
                abandoned.get(),
                completed.get(),
                unknownCompletions.get(),
                unknownProducers.get(),
                eventRequested.get(),
                eventRefused.get(),
                eventExpired.get(),
                eventAbandoned.get(),
                eventCallerGone.get(),
                eventStatusesDropped.get(),
                resets.get(),
                resetDropped.get(),
                resetRequestsFailed.get(),
                factsDropped.get(),
                factsFailed.get());
    }
}
