package com.oddin.oddsfeedsdk.internal.rest;

/**
 * Which permits an API call waits for. Each pool has its own, so a burst of entity loads cannot
 * hold up a recovery request or the market descriptions.
 */
public enum Pool {
    /** Recovery requests, and the producer list and whoami that recovery depends on: 2 at once. */
    RECOVERY,
    /** Market descriptions, void reasons, match status descriptions and sports: 2 at once. */
    CATALOG,
    /** Everything else - entities, schedules, fixture changes, replay: the REST concurrency limit. */
    DATA
}
