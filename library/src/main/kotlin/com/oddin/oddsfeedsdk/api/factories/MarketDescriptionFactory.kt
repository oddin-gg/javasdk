package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.cache.entity.CompetitorCache
import com.oddin.oddsfeedsdk.cache.entity.PlayerCache
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionCache
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionImpl
import com.oddin.oddsfeedsdk.cache.market.MarketVoidReasonsCache
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import java.util.*

interface OutcomeDescription {
    val id: String
    @Deprecated("This attribute is deprecated and will be removed in future.")
    val refId: Long?
    fun getName(locale: Locale): String?
    fun getDescription(locale: Locale): String?
}

interface Specifier {
    val name: String
    val type: String
}

data class SpecifierImpl(override val name: String, override val type: String) : Specifier

enum class OutcomeType {
    PLAYER, COMPETITOR;
}

interface MarketDescription {
    val id: Int
    @Deprecated("This attribute is deprecated and will be removed in future.")
    val refId: Int?

    fun getName(locale: Locale): String?
    val outcomes: List<OutcomeDescription>
    val variant: String?
    val specifiers: List<Specifier>?
    val includesOutcomesOfType: String?
    val outcomeType: OutcomeType?
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

    fun getMarketDescription(
            marketId: Int,
            variant: String?,
            locales: List<Locale>
    ): MarketDescription?

    fun getMarketDescriptions(locale: Locale): List<MarketDescription>
    fun getMarketVoidReasons(): List<MarketVoidReason>
    fun getMarketVoidReason(id: Int): MarketVoidReason?

    val playerCache: PlayerCache
    val competitorCache: CompetitorCache
}

class MarketDescriptionFactoryImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val marketDescriptionCache: MarketDescriptionCache,
    private val marketVoidReasonsCache: MarketVoidReasonsCache,
    override val playerCache: PlayerCache,
    override val competitorCache: CompetitorCache
) : MarketDescriptionFactory {

    override fun getMarketDescription(
        marketId: Int,
        specifiers: Map<String, String>?,
        locales: List<Locale>
    ): MarketDescription? {
        return getMarketDescription(
            marketId,
            specifiers?.get("variant"),
            locales,
        )
    }

    override fun getMarketDescription(
            marketId: Int,
            variant: String?,
            locales: List<Locale>
    ): MarketDescription? {

        val md = marketDescriptionCache.getMarketDescription(marketId, variant, locales) ?: return null

        return MarketDescriptionImpl(
                marketId,
                md.includesOutcomesOfType,
                md.outcomeType,
                variant,
                marketDescriptionCache,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet()
        )
    }

    override fun getMarketDescriptions(locale: Locale): List<MarketDescription> {
        val keys = marketDescriptionCache.getMarketDescriptions(locale)

        return keys.map {
            MarketDescriptionImpl(
                it.key.marketId,
                it.value.includesOutcomesOfType,
                it.value.outcomeType,
                it.key.variant,
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
