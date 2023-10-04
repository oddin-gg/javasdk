package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface PlayerCache : Closable {
    fun clearCacheItem(id: URN)
    fun getPlayer(id: URN, locales: Set<Locale>): LocalizedPlayer?
}

private val logger = KotlinLogging.logger {}

class PlayerCacheImpl @Inject constructor(
    private val apiClient: ApiClient,
    private val oddsFeedConfiguration: OddsFeedConfiguration,
) : PlayerCache {
    private val lock = Any()

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(24L, TimeUnit.HOURS)
            .build<URN, LocalizedPlayer>()


    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getPlayer(id: URN, locales: Set<Locale>): LocalizedPlayer? {
        return synchronized(lock) {
            val localizedPlayer = internalCache.getIfPresent(id)
            val localeSet = localizedPlayer?.loadedLocales ?: emptySet()
            val toFetchLocales = locales.filter { !localeSet.contains(it) }

            if (toFetchLocales.isNotEmpty()) {
                loadAndCacheItem(id, toFetchLocales)
            }

            return@synchronized internalCache.getIfPresent(id)
        }
    }

    override fun close() {}

    private fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        runBlocking {
            locales.forEach {
                val data = try {
                    apiClient.fetchPlayerProfile(id, it)
                } catch (e: Exception) {
                    return@forEach
                }

                try {
                    refreshOrInsertItem(id, it, data)
                } catch (e: Exception) {
                    logger.error { "Failed to refresh or insert player" }
                }
            }
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, data: RAPlayerProfileEndpoint.Player) {
        var item = internalCache.getIfPresent(id)

        if (item == null) {
            item = LocalizedPlayer(
                id
            )
        }

        item.name[locale] = data.name
        item.fullName[locale] = data.fullName

        internalCache.put(id, item)
    }
}

data class LocalizedPlayer(
    val id: URN,
) : LocalizedItem {
    val name = ConcurrentHashMap<Locale, String>()
    val fullName = ConcurrentHashMap<Locale, String>()

    override val loadedLocales: Set<Locale>
        get() {
            val locales = mutableSetOf<Locale>()
            // @TODO This can be tricky if I have different values in each locale cache
            locales.addAll(name.keys)
            locales.addAll(fullName.keys)

            return locales
        }
}

class PlayerImpl(
    override val id: URN,
    private val playerCache: PlayerCache,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>,
) : Player {
    override val names: Map<Locale, String>?
        get() = fetchPlayer(locales)?.name

    override fun getName(locale: Locale): String? {
        return fetchPlayer(setOf(locale))?.name?.get(locale)
    }

    override val fullNames: Map<Locale, String>?
        get() = fetchPlayer(locales)?.fullName

    override fun getFullName(locale: Locale): String? {
        return fetchPlayer(setOf(locale))?.fullName?.get(locale)
    }

    private fun fetchPlayer(locales: Set<Locale>): LocalizedPlayer? {
        val item = playerCache.getPlayer(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Player $id not found", null)
        } else {
            item
        }
    }
}

