package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.cache.market.CompositeKey
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionCache
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionImpl
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import java.util.*

interface OutcomeDescription {
    val id: Long
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

    fun getName(locale: Locale): String?
    val outcomes: List<OutcomeDescription>
    val variant: String?
    val specifiers: List<Specifier>?
}

interface MarketDescriptionFactory {
    fun getMarketDescription(
        marketId: Int,
        specifiers: Map<String, String>?,
        locales: List<Locale>
    ): MarketDescription?

    fun getMarketDescriptions(locale: Locale): List<MarketDescription>
}

class MarketDescriptionFactoryImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val marketDescriptionCache: MarketDescriptionCache
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
}