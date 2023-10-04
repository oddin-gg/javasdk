package com.oddin.oddsfeedsdk.api

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.factories.MarketDescription
import com.oddin.oddsfeedsdk.api.factories.MarketDescriptionFactory
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.config.ExceptionHandler
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import java.util.*

interface MarketDescriptionManager {
    /**
     * Fetch all market descriptions with default localization
     *
     * @return a list of available  market descriptions
     */
    val marketDescriptions: List<MarketDescription>?

    /**
     * Fetch all market descriptions with selected localization
     *
     * @param locale - {@link Locale} for data
     * @return a list of available  market descriptions
     */
    fun getMarketDescriptions(locale: Locale): List<MarketDescription>?

    /**
     * Fetch market description with selected localization
     *
     * @param marketId market id
     * @param variant optional market variant
     * @param locale - {@link Locale} for data
     * @return a market descriptions
     */
    fun getMarketDescription(marketId: Int, variant: String?, locale: Locale): MarketDescription?

    /**
     * Clear market description from all caches
     * @param marketId market id to be deleted
     * @param variant optional market variant
     */
    fun clearMarketDescription(marketId: Int, variant: String?)

    /**
     * @return a list of available market void reasons
     */
    val marketVoidReasons: List<MarketVoidReason>?

    /**
     * Clear void reasons from all caches
     */
    fun clearMarketVoidReasons()

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

    override fun getMarketDescription(
            marketId: Int,
            variant: String?,
            locale: Locale): MarketDescription? {

        val callable = {
            marketDescriptionFactory.getMarketDescription(marketId, variant, listOf(locale))
        }

        return wrapError(callable, "getMarketDescriptionByIdAndVariant")
    }


    override fun clearMarketDescription(marketId: Int, variant: String?) {
        cacheManager.marketDescriptionCache.clearCacheItem(marketId, variant)
    }

    override val marketVoidReasons: List<MarketVoidReason>?
        get() {
            val callable = {
                marketDescriptionFactory.getMarketVoidReasons()
            }

            return wrapError(callable, "marketVoidReasons")
        }

    override fun clearMarketVoidReasons() {
        cacheManager.marketVoidReasonsCache.clearCache()
    }

}
