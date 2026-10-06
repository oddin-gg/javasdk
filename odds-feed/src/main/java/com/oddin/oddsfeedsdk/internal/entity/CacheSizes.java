package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;

/**
 * How many entries the caches a client can size hold each, as 0.0.x's four options set them. The
 * live state of matches follows the match cache: it holds one record per match, read with it.
 * Tournaments and sports, which no option sizes, keep their fixed bound.
 *
 * <p>A size under one is one. A read takes what it loaded from the cache, and a cache of none drops
 * it before the read does: the entity would read as not found, as on 0.0.x, whose options took any
 * size.
 */
public record CacheSizes(long matches, long fixtures, long competitors, long players) {

    public CacheSizes {
        matches = Math.max(1, matches);
        fixtures = Math.max(1, fixtures);
        competitors = Math.max(1, competitors);
        players = Math.max(1, players);
    }

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
