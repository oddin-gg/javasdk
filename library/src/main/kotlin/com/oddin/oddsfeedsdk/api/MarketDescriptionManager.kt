package com.oddin.oddsfeedsdk.api

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.factories.MarketDescription
import com.oddin.oddsfeedsdk.api.factories.MarketDescriptionFactory
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.config.ExceptionHandler
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import java.util.*

interface MarketDescriptionManager {
    val marketDescriptions: List<MarketDescription>?
    fun getMarketDescriptions(locale: Locale): List<MarketDescription>?
    fun clearMarketDescription(marketId: Int, variant: String?)
}

class MarketDescriptionManagerImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val marketDescriptionFactory: MarketDescriptionFactory,
    private val cacheManager: CacheManager
) :
    MarketDescriptionManager, ExceptionHandler {

    override val exceptionHandlingStrategy: ExceptionHandlingStrategy
        get() = oddsFeedConfiguration.exceptionHandlingStrategy

    override val marketDescriptions: List<MarketDescription>?
        get() = getMarketDescriptions(oddsFeedConfiguration.defaultLocale)

    override fun getMarketDescriptions(locale: Locale): List<MarketDescription>? {
        val callable = {
            marketDescriptionFactory.getMarketDescriptions(locale)
        }

        return wrapError(callable, "marketDescriptions")
    }

    override fun clearMarketDescription(marketId: Int, variant: String?) {
        cacheManager.marketDescriptionCache.clearCacheItem(marketId, variant)
    }

}