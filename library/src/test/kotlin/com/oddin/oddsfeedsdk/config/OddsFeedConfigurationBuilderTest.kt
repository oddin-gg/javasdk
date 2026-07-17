package com.oddin.oddsfeedsdk.config

import org.junit.Assert.assertEquals
import org.junit.Test

// CORE-3581: the cache size caps must be exposed on the builder with sensible
// defaults and be overridable per client.
class OddsFeedConfigurationBuilderTest {

    private fun builder() = OddsFeedConfigurationBuilder()
        .setAccessToken("token")
        .selectTest()

    @Test
    fun defaultCacheSizeCapsAreApplied() {
        val config = builder().build()

        assertEquals(OddsFeedConfiguration.DEFAULT_MAX_MATCH_CACHE_SIZE, config.maxMatchCacheSize)
        assertEquals(OddsFeedConfiguration.DEFAULT_MAX_FIXTURE_CACHE_SIZE, config.maxFixtureCacheSize)
        assertEquals(OddsFeedConfiguration.DEFAULT_MAX_COMPETITOR_CACHE_SIZE, config.maxCompetitorCacheSize)
        assertEquals(OddsFeedConfiguration.DEFAULT_MAX_PLAYER_CACHE_SIZE, config.maxPlayerCacheSize)
    }

    @Test
    fun cacheSizeCapsAreOverridable() {
        val config = builder()
            .setMaxMatchCacheSize(11)
            .setMaxFixtureCacheSize(22)
            .setMaxCompetitorCacheSize(33)
            .setMaxPlayerCacheSize(44)
            .build()

        assertEquals(11L, config.maxMatchCacheSize)
        assertEquals(22L, config.maxFixtureCacheSize)
        assertEquals(33L, config.maxCompetitorCacheSize)
        assertEquals(44L, config.maxPlayerCacheSize)
    }
}
