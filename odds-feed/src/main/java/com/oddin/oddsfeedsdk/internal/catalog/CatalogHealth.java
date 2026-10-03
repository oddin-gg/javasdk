package com.oddin.oddsfeedsdk.internal.catalog;

import java.time.Duration;

/**
 * How a catalog is doing, for the feed's health.
 *
 * @param name such as {@code market descriptions}
 * @param servedStale reads served a value older than the refresh age
 * @param staleFor how long the stalest value has been served stale, zero when none is
 * @param failedFetches fetches that failed, refreshes and first loads alike
 * @param failing keys whose last fetch failed
 * @param evictedForRoom values the size bound dropped
 */
public record CatalogHealth(
        String name, long servedStale, Duration staleFor, long failedFetches, long failing, long evictedForRoom) {}
