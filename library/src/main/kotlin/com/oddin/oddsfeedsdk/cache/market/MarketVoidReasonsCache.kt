package com.oddin.oddsfeedsdk.cache.market

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketVoidReasons.VoidReason.Param
import kotlinx.coroutines.runBlocking
import javax.xml.bind.JAXBElement

interface MarketVoidReasonsCache {
    fun getMarketVoidReasons(): List<MarketVoidReason>
    fun getMarketVoidReason(id: Int): MarketVoidReason?
    fun clearCache()
}

class MarketVoidReasonsCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MarketVoidReasonsCache {
    private val lock = Any()

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .build<Int, MarketVoidReason>()

    override fun getMarketVoidReasons(): List<MarketVoidReason> {
        return synchronized(lock) {
            if (internalCache.size() == 0L) {
                loadAndCacheItem()
            }

            return@synchronized internalCache.asMap().values.sortedBy { it.id }
        }
    }

    override fun getMarketVoidReason(id: Int): MarketVoidReason? {
        return synchronized(lock) {
            if (internalCache.size() == 0L) {
                loadAndCacheItem()
            }

            return@synchronized internalCache.getIfPresent(id)
        }
    }

    override fun clearCache() {
        internalCache.invalidateAll()
    }

    private fun loadAndCacheItem() {
        runBlocking {
            val marketVoidReasons = apiClient.fetchMarketVoidReasons()
            marketVoidReasons.forEach { voidReason ->
                val params = voidReason.content.mapNotNull { (it as? JAXBElement<Param>)?.value?.name }
                val id = voidReason.id
                val item = MarketVoidReasonImpl(
                    id,
                    voidReason.name,
                    voidReason.description,
                    voidReason.template,
                    params
                )
                internalCache.put(id, item)
            }
        }
    }
}

class MarketVoidReasonImpl(
    override val id: Int,
    override val name: String?,
    override val description: String?,
    override val template: String?,
    override val params: List<String>
) : MarketVoidReason

