package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.sportevent.Fixture
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannelImpl
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.utils.Utils
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface FixtureCache : CacheLoader<LocalizedFixture> {
    fun getFixture(id: URN, locale: Locale): LocalizedFixture?
}

class FixtureCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : FixtureCache {

    private val lock = Any()
    private val internalCache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(12L, TimeUnit.HOURS)
        .build<URN, LocalizedFixture>()

    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getFixture(id: URN, locale: Locale): LocalizedFixture? {
        val item = internalCache.getIfPresent(id)

        if (item == null) {
            loadAndCacheItem(id, locale)
        }

        return internalCache.getIfPresent(id)
    }

    override fun getLock(): Any {
        return lock
    }

    override fun getCache(): Cache<URN, LocalizedFixture> {
        return internalCache
    }

    override fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        locales.forEach {
            loadAndCacheItem(id, it)
        }
    }

    override fun getSupportedURNType(): String? {
        return null
    }

    private fun loadAndCacheItem(id: URN, locale: Locale) {
        runBlocking {
            val data = try {
                apiClient.fetchFixture(id, locale)
            } catch (e: Exception) {
                return@runBlocking
            }

            val fixture = LocalizedFixture(
                Utils.parseDate(data.startTime),
                ConcurrentHashMap(data.extraInfo.info.associate { it.key to it.value }),
                data.tvChannels?.tvChannel?.map { TvChannelImpl(it.name, it.streamUrl, it.language) } ?: emptyList()
            )

            internalCache.put(id, fixture)
        }
    }

}

data class LocalizedFixture(
    val startTime: Date?,
    val extraInfo: ConcurrentHashMap<String, String>,
    val tvChannels: List<TvChannel>
) : LocalizedItem {

    override val loadedLocales: Set<Locale> = emptySet()
}

class FixtureImpl(
    val id: URN,
    private val fixtureCache: FixtureCache,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : Fixture {

    override val startTime: Date?
        get() = fetchFixture()?.startTime

    override val extraInfo: Map<String, String>?
        get() = fetchFixture()?.extraInfo

    override val tvChannels: List<TvChannel>?
        get() = fetchFixture()?.tvChannels

    private fun fetchFixture(): LocalizedFixture? {
        val item = fixtureCache.getFixture(id, locales.first())

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
        } else {
            item
        }
    }

}
