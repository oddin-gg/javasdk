package com.oddin.oddsfeedsdk.subscribe;

/** How well a part of the feed works. New in 1.0. */
public enum HealthState {
    /** As it should. */
    HEALTHY,
    /** It works, but less well than it should: behind, slow, or serving data it could not refresh. */
    DEGRADED,
    /** It does not work: stuck, or waiting on something that will not come. */
    UNHEALTHY
}
