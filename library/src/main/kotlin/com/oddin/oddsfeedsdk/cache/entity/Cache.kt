package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.Cache
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange
import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*
import kotlin.collections.emptySet as emptySet

interface CacheLoader<T : LocalizedItem> {
    fun getLock(): Any

    fun getCache(): Cache<URN, T>

    fun loadFromCache(id: URN, locales: Set<Locale>): T? {
        return synchronized(getLock()) {
            val localizedTournament = getCache().getIfPresent(id)
            val localeSet = localizedTournament?.loadedLocales ?: emptySet<T>()
            val toFetchLocales = locales.filter { !localeSet.contains(it) }

            if (toFetchLocales.isNotEmpty()) {
                loadAndCacheItem(id, toFetchLocales)
            }

            return@synchronized getCache().getIfPresent(id)
        }
    }

    fun loadAndCacheItem(id: URN, locales: List<Locale>)

    fun onFeedMessageReceived(id: URN, feedMessage: FeedMessage) {
        when {
            feedMessage.message as? OFFixtureChange == null -> return
            getSupportedURNType() != null && id.type != getSupportedURNType() -> return
            else -> clearCacheItem(id)
        }
    }

    fun getSupportedURNType(): String?

    fun clearCacheItem(id: URN)
}