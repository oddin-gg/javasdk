package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;

/**
 * How many entries the caches a client can size hold each, as 0.0.x's four options set them. The
 * live state of matches follows the match cache: it holds one record per match, read with it.
 * Tournaments and sports, which no option sizes, keep their fixed bound.
 */
public record CacheSizes(long matches, long fixtures, long competitors, long players) {

    /** 0.0.x's defaults, for a test or a benchmark that has no configuration. */
    public static final CacheSizes DEFAULTS = new CacheSizes(
            OddsFeedConfiguration.DEFAULT_MAX_MATCH_CACHE_SIZE,
            OddsFeedConfiguration.DEFAULT_MAX_FIXTURE_CACHE_SIZE,
            OddsFeedConfiguration.DEFAULT_MAX_COMPETITOR_CACHE_SIZE,
            OddsFeedConfiguration.DEFAULT_MAX_PLAYER_CACHE_SIZE);

    /** What the configuration sets. */
    public static CacheSizes from(OddsFeedConfiguration configuration) {
        return new CacheSizes(
                configuration.getMaxMatchCacheSize(),
                configuration.getMaxFixtureCacheSize(),
                configuration.getMaxCompetitorCacheSize(),
                configuration.getMaxPlayerCacheSize());
    }
}
