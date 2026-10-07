package com.oddin.oddsfeedsdk.subscribe;

/** How well a part of the feed works. New in 1.0. */
public enum HealthState {
    /** As it should. */
    HEALTHY,
    /** It works, but less well than it should: behind, slow, or serving data it could not refresh. */
    DEGRADED,
    /**
     * It does not move: a callback that has not returned, a queue that has not moved while not
     * empty, or a deadlock. The feed cannot unblock it; the remedy is to close the feed and open a
     * new one.
     */
    STALLED
}
