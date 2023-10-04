package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface MarketData {
    fun getMarketName(locale: Locale): String?
    fun getOutcomeName(id: String, locale: Locale): String?
}

interface MarketDataFactory {
    fun buildMarketData(
        event: SportEvent,
        marketId: Int,
        specifiers: Map<String, String>
    ): MarketData
}

class MarketDataFactoryImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val marketDescriptionFactory: MarketDescriptionFactory
) :
    MarketDataFactory {
    override fun buildMarketData(event: SportEvent, marketId: Int, specifiers: Map<String, String>): MarketData {
        return MarketDataImpl(
            marketId,
            specifiers,
            marketDescriptionFactory,
            event,
            oddsFeedConfiguration.exceptionHandlingStrategy
        )
    }
}

class MarketDataImpl(
    private val marketId: Int,
    private val specifiers: Map<String, String>,
    private val marketDescriptionFactory: MarketDescriptionFactory,
    private val sportEvent: SportEvent,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy
) : MarketData {

    override fun getMarketName(locale: Locale): String? {
        val marketDescription = marketDescriptionFactory.getMarketDescription(marketId, specifiers, listOf(locale))
        val marketName = marketDescription?.getName(locale)

        return when {
            marketName == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot find market name",
                null
            )
            marketName == null -> null
            else -> makeMarketName(marketName, locale)
        }
    }

    override fun getOutcomeName(id: String, locale: Locale): String? {
        val marketDescription = marketDescriptionFactory.getMarketDescription(marketId, specifiers, listOf(locale))
        var outcomeName = marketDescription?.outcomes?.firstOrNull { it.id == id }?.getName(locale)

        if (outcomeName == null && marketDescription?.outcomeType != null) {
            when (marketDescription.outcomeType) {
                OutcomeType.PLAYER -> {
                    val player = marketDescriptionFactory.playerCache.getPlayer(URN.parse(id), setOf(locale))
                    outcomeName = player?.name?.values?.first()
                }
                OutcomeType.COMPETITOR -> {
                    val competitor = marketDescriptionFactory.competitorCache.getCompetitor(URN.parse(id), setOf(locale))
                    outcomeName = competitor?.name?.values?.first()
                }
                else -> {}
            }
        }

        return when {
            outcomeName == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot find outcome name",
                null
            )
            outcomeName == null -> null
            else -> makeOutcomeName(outcomeName, locale)
        }
    }

    private fun makeOutcomeName(outcomeName: String, locale: Locale): String? {
        return when (outcomeName) {
            "home" -> (sportEvent as? Match)?.homeCompetitor?.getName(locale)
            "away" -> (sportEvent as? Match)?.awayCompetitor?.getName(locale)
            else -> outcomeName
        }
    }

    private fun makeMarketName(marketName: String, locale: Locale): String {
        if (specifiers.isEmpty()) return marketName

        var template = marketName
        specifiers.forEach {
            val key = "{${it.key}}"
            if (template.indexOf("{${it.key}}") == -1) {
                return@forEach
            }

            val value = when (it.value) {
                "home" -> (sportEvent as? Match)?.homeCompetitor?.getName(locale)
                "away" -> (sportEvent as? Match)?.awayCompetitor?.getName(locale)
                else -> it.value
            } ?: it.value

            template = template.replace(key, value)
        }
        return template
    }

}
