package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionCache
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionImpl
import com.oddin.oddsfeedsdk.cache.market.MarketVoidReasonsCache
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import java.util.*

interface OutcomeDescription {
    val id: Long
    val refId: Long?
    fun getName(locale: Locale): String?
    fun getDescription(locale: Locale): String?
}

interface Specifier {
    val name: String
    val type: String
}

data class SpecifierImpl(override val name: String, override val type: String) : Specifier

interface MarketDescription {
    val id: Int
    val refId: Int?

    fun getName(locale: Locale): String?
    val outcomes: List<OutcomeDescription>
    val variant: String?
    val specifiers: List<Specifier>?
}

interface MarketVoidReason {
    val id: Int

    val name: String?
    val description: String?
    val template: String?
    val params: List<String>?
}

interface MarketDescriptionFactory {
    fun getMarketDescription(
        marketId: Int,
        specifiers: Map<String, String>?,
        locales: List<Locale>
    ): MarketDescription?

    fun getMarketDescriptions(locale: Locale): List<MarketDescription>
    fun getMarketVoidReasons(): List<MarketVoidReason>
    fun getMarketVoidReason(id: Int): MarketVoidReason?
}

class MarketDescriptionFactoryImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val marketDescriptionCache: MarketDescriptionCache,
    private val marketVoidReasonsCache: MarketVoidReasonsCache
) :
    MarketDescriptionFactory {
    override fun getMarketDescription(
        marketId: Int,
        specifiers: Map<String, String>?,
        locales: List<Locale>
    ): MarketDescription? {
        return MarketDescriptionImpl(
            marketId,
            specifiers?.get("variant"),
            marketDescriptionCache,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }

    override fun getMarketDescriptions(locale: Locale): List<MarketDescription> {
        val keys = marketDescriptionCache.getMarketDescriptions(locale)

        return keys.map {
            MarketDescriptionImpl(
                it.marketId,
                it.variant,
                marketDescriptionCache,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                setOf(locale)
            )
        }
    }

    override fun getMarketVoidReasons(): List<MarketVoidReason> {
        return marketVoidReasonsCache.getMarketVoidReasons()
    }

    override fun getMarketVoidReason(id: Int): MarketVoidReason? {
        return marketVoidReasonsCache.getMarketVoidReason(id)
    }
}