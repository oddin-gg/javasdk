package com.oddin.oddsfeedsdk.cache.market

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.factories.MarketDescription
import com.oddin.oddsfeedsdk.api.factories.OutcomeDescription
import com.oddin.oddsfeedsdk.api.factories.Specifier
import com.oddin.oddsfeedsdk.api.factories.SpecifierImpl
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescription
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CompositeKey(val marketId: Int, val variant: String?) {

    private val key: String = "$marketId-${variant ?: "*"}"

    override fun hashCode(): Int {
        return key.hashCode()
    }

    override fun toString(): String {
        return key
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompositeKey
        return other.key == key
    }
}

interface MarketDescriptionCache {
    fun getMarketDescriptions(locale: Locale): List<CompositeKey>

    fun getMarketDescription(
        marketId: Int,
        variant: String?,
        locales: List<Locale>
    ): LocalizedMarketDescription?

    fun getMarketDescription(
        key: CompositeKey
    ): LocalizedMarketDescription?

    fun clearCacheItem(marketId: Int, variant: String?)
}

private val logger = KotlinLogging.logger {}

class MarketDescriptionCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MarketDescriptionCache {
    private val lock = Any()

    private val loadedLocales = mutableSetOf<Locale>()

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .build<CompositeKey, LocalizedMarketDescription>()

    override fun getMarketDescriptions(locale: Locale): List<CompositeKey> {
        return synchronized(lock) {
            val needReload = !loadedLocales.contains(locale)

            if (needReload) {
                loadAndCacheItem(listOf(locale))
            }

            return@synchronized internalCache.asMap().filter { it.value.loadedLocales.contains(locale) }.keys.toList()
        }
    }

    override fun getMarketDescription(
        marketId: Int,
        variant: String?,
        locales: List<Locale>
    ): LocalizedMarketDescription? {
        return synchronized(lock) {
            val key = CompositeKey(marketId, variant)
            val localizedMarketDescription = internalCache.getIfPresent(key)
            val localeSet = localizedMarketDescription?.loadedLocales ?: emptySet()
            val toFetchLocales = locales.filter { !localeSet.contains(it) }

            if (toFetchLocales.isNotEmpty()) {
                loadAndCacheItem(toFetchLocales)
            }

            return@synchronized internalCache.getIfPresent(key)
        }
    }

    override fun getMarketDescription(key: CompositeKey): LocalizedMarketDescription? {
        return internalCache.getIfPresent(key)
    }

    override fun clearCacheItem(marketId: Int, variant: String?) {
        internalCache.invalidate(CompositeKey(marketId, variant))
    }

    private fun loadAndCacheItem(locales: List<Locale>) {
        runBlocking {
            locales.forEach { locale ->
                val marketDescriptions = try {
                    apiClient.fetchMarketDescriptions(locale)
                } catch (e: Exception) {
                    return@forEach
                }

                marketDescriptions.forEach {
                    try {
                        refreshOrInsertItem(it, locale)
                    } catch (e: Exception) {
                        logger.error { "Failed to refresh or insert market" }
                    }
                }

                // Add locale to loaded locales
                loadedLocales.add(locale)
            }
        }
    }

    private fun refreshOrInsertItem(marketDescription: RAMarketDescription, locale: Locale) {
        val key = CompositeKey(marketDescription.id, marketDescription.variant)
        var item = internalCache.getIfPresent(key)
        val specifiers = marketDescription.specifiers?.specifier?.map { SpecifierImpl(it.name, it.type) }

        if (item == null) {
            val outcomes =
                marketDescription.outcomes.outcome.map { it.id to LocalizedOutcomeDescription() }.toMap()
            item = LocalizedMarketDescription(
                marketDescription.refId,
                ConcurrentHashMap(outcomes)
            )
        }

        marketDescription.outcomes.outcome.forEach {
            val localizedOutcomeDescription = item.outcomes[it.id]
            localizedOutcomeDescription?.name?.put(locale, it.name)
            localizedOutcomeDescription?.refId = it.refId

            if (it.description != null) {
                localizedOutcomeDescription?.description?.put(locale, it.description)
            }
        }

        item.name[locale] = marketDescription.name
        item.specifiers = specifiers

        internalCache.put(key, item)
    }
}


data class LocalizedMarketDescription(
    val refId: Int?,
    var outcomes: ConcurrentHashMap<Long, LocalizedOutcomeDescription>
) : LocalizedItem {
    var specifiers: List<SpecifierImpl>? = null
    var name = ConcurrentHashMap<Locale, String>()

    override val loadedLocales: Set<Locale>
        get() = name.keys
}

class LocalizedOutcomeDescription {
    var name = ConcurrentHashMap<Locale, String>()
    var description = ConcurrentHashMap<Locale, String>()
    var refId: Long? = null
}

class MarketDescriptionImpl(
    override val id: Int,
    override val variant: String?,
    private val marketDescriptionCache: MarketDescriptionCache,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : MarketDescription {

    override val refId: Int?
        get() = fetchMarketDescription(locales)?.refId

    override fun getName(locale: Locale): String? {
        return fetchMarketDescription(setOf(locale))?.name?.get(locale)
    }

    override val outcomes: List<OutcomeDescription>
        get() = fetchMarketDescription(locales)?.outcomes?.map {
            OutcomeDescriptionImpl(
                it.key,
                it.value
            )
        }
            ?: emptyList()

    override val specifiers: List<Specifier>?
        get() = fetchMarketDescription(locales)?.specifiers

    private fun fetchMarketDescription(locales: Set<Locale>): LocalizedMarketDescription? {
        val item = marketDescriptionCache.getMarketDescription(id, variant, locales.toList())

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Market description not found", null)
        } else {
            item
        }
    }
}

class OutcomeDescriptionImpl(
    override val id: Long,
    private val localizedOutcomeDescription: LocalizedOutcomeDescription
) : OutcomeDescription {

    override val refId: Long?
        get() = localizedOutcomeDescription.refId

    override fun getName(locale: Locale): String? {
        return localizedOutcomeDescription.name[locale]
    }

    override fun getDescription(locale: Locale): String? {
        return localizedOutcomeDescription.description[locale]
    }
}