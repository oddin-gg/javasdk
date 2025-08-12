package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
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
    private val subscription: Disposable

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(24L, TimeUnit.HOURS)
            .build<URN, LocalizedPlayer>()

    init {
        subscription = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .observeOn(Schedulers.io())
            .subscribe({ response ->
                val locale = response.first ?: return@subscribe
                val data = response.second ?: return@subscribe

                val players = when (data) {
                    is RACompetitorProfileEndpoint -> data.players
                    else -> null
                }

                if (players != null) {
                    synchronized(lock) {
                        handlePlayersData(locale, players.map { it.id })
                    }
                }
            }, {
                logger.error { "Failed to process message in player cache - $it" }
            })
    }

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

    override fun close() {
        subscription.dispose()
    }

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
        if (data.fullName != null) {
            item.fullName[locale] = data.fullName!!
        }
        item.sportID[locale] = data.sportID

        internalCache.put(id, item)
    }

    private fun handlePlayersData(locale: Locale, playersIDs: List<String>) {
        runBlocking {
            playersIDs.forEach { id ->
                val urn = try {
                    URN.parse(id)
                } catch (e: Exception) {
                    val msg = "Failed to parse id [$id] to urn"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }

                val data = try {
                    apiClient.fetchPlayerProfile(urn, locale)
                } catch (e: Exception) {
                    val msg = "Failed to fetch player profile for id: [$id], locale: [$locale]"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }

                try {
                    refreshOrInsertItem(urn, locale, data)
                } catch (e: Exception) {
                    val msg = "Failed to refresh or insert player for id: [$id], locale: [$locale]"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }
            }
        }
    }
}

data class LocalizedPlayer(
    val id: URN,
) : LocalizedItem {
    val name = ConcurrentHashMap<Locale, String>()
    val fullName = ConcurrentHashMap<Locale, String>()
    val sportID = ConcurrentHashMap<Locale, String>()

    override val loadedLocales: Set<Locale>
        get() {
            val locales = mutableSetOf<Locale>()
            // @TODO This can be tricky if I have different values in each locale cache
            locales.addAll(name.keys)
            locales.addAll(fullName.keys)
            locales.addAll(sportID.keys)

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

    override val sportIDs: Map<Locale, String>?
        get() = fetchPlayer(locales)?.sportID

    override fun getSportID(locale: Locale): String? {
        return fetchPlayer(setOf(locale))?.sportID?.get(locale)
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

