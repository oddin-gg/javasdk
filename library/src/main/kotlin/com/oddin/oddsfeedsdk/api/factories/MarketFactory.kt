package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.mq.entities.*
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlementMarket
import com.oddin.oddsfeedsdk.schema.feed.v1.OFMarket
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChangeMarket
import mu.KotlinLogging
import java.util.*

interface FeedMessageMarket {
    val id: Int
    val specifiers: String
}

interface FeedMarketOutcome

interface MarketFactory {
    fun <T : Market> buildMarket(
        event: SportEvent,
        market: FeedMessageMarket
    ): T?
}

private val logger = KotlinLogging.logger {}

class MarketFactoryImpl @Inject constructor(
    config: OddsFeedConfiguration,
    private val marketDataFactory: MarketDataFactory
) : MarketFactory {
    private val locales = listOf(config.defaultLocale)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Market> buildMarket(event: SportEvent, market: FeedMessageMarket): T? {
        return when (market) {
            is OFOddsChangeMarket -> buildOddsChangeMarket(event, market) as T
            is OFBetSettlementMarket -> buildBetSettlementMarket(event, market) as T
            is OFMarket -> buildBetCancelMarket(event, market) as T
            else -> null
        }
    }

    private fun buildOddsChangeMarket(event: SportEvent, market: OFOddsChangeMarket): MarketWithOdds? {
        val specifiersMap = extractSpecifiers(market.specifiers)
        val marketData = marketDataFactory.buildMarketData(event, market.id, specifiersMap)
        val outcomeOdds = buildOutcomes<OutcomeOdds>(
            marketData,
            event,
            market.outcome
        )

        return MarketWithOddsImpl(
            market.id,
            market.refId,
            specifiersMap,
            marketData,
            locales.first(),
            outcomeOdds,
            market.status,
            market.favourite
        )
    }

    private fun buildBetSettlementMarket(
        event: SportEvent,
        market: OFBetSettlementMarket
    ): MarketWithSettlement? {
        val specifiersMap = extractSpecifiers(market.specifiers)
        val marketData = marketDataFactory.buildMarketData(event, market.id, specifiersMap)
        val outcomeSettlement = buildOutcomes<OutcomeSettlement>(
            marketData,
            event,
            market.outcome
        )

        return MarketWithSettlementImpl(
            market.id,
            market.refId,
            specifiersMap,
            marketData,
            locales.first(),
            outcomeSettlement
        )
    }

    private fun buildBetCancelMarket(event: SportEvent, market: OFMarket): MarketCancel? {
        val specifiersMap = extractSpecifiers(market.specifiers)
        val marketData = marketDataFactory.buildMarketData(event, market.id, specifiersMap)

        return MarketCancelImpl(
            market.id,
            market.refId,
            specifiersMap,
            marketData,
            market.voidReasonId,
            market.voidReasonParams,
            locales.first()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <S : Outcome> buildOutcomes(
        marketData: MarketData,
        event: SportEvent,
        outcomes: List<FeedMarketOutcome>
    ): List<S> {
        return outcomes.mapNotNull {
            when (it) {
                is OFOddsChangeMarket.OFOutcome -> buildOutcomeOdds(
                    it,
                    marketData,
                    event,
                    locales.first()
                )
                is OFBetSettlementMarket.OFOutcome -> buildOutcomeSettlement(
                    it,
                    marketData,
                    locales.first()
                )
                else -> null
            }
        }.toList() as List<S>
    }

    private fun buildOutcomeOdds(
        outcome: OFOddsChangeMarket.OFOutcome,
        marketData: MarketData,
        event: SportEvent,
        locale: Locale
    ): OutcomeOdds {
        val additionalProbabilities = if (outcome.hasAdditionalProbabilities()) {
            AdditionalProbabilitiesImpl(
                outcome.winProbabilities,
                outcome.loseProbabilities,
                outcome.halfWinProbabilities,
                outcome.halfLoseProbabilities,
                outcome.refundProbabilities
            )
        } else {
            null
        }

        val isValidTeam = event is Match && outcome.team != null
        return if (isValidTeam) {
            CompetitorOutcomeOddsImpl(
                event as Match,
                outcome.team,
                outcome.odds,
                outcome.probabilities,
                outcome.active,
                outcome.id,
                outcome.refId,
                marketData,
                locale,
                additionalProbabilities
            )
        } else {
            OutcomeOddsImpl(
                outcome.odds,
                outcome.probabilities,
                outcome.active,
                outcome.id,
                outcome.refId,
                marketData,
                locale,
                additionalProbabilities
            )
        }
    }

    private fun buildOutcomeSettlement(
        outcome: OFBetSettlementMarket.OFOutcome,
        marketData: MarketData,
        locale: Locale
    ): OutcomeSettlement {
        return OutcomeSettlementImpl(
            outcome.id,
            outcome.refId,
            marketData,
            locale,
            outcome.voidFactor,
            outcome.deadHeatFactor,
            outcome.result
        )
    }

    private fun extractSpecifiers(specifiers: String?): Map<String, String> {
        if (specifiers.isNullOrEmpty()) {
            return emptyMap()
        }

        val parts = specifiers.split("|")
        val result = mutableMapOf<String, String>()
        parts.forEach {
            val variant = it.split("=")
            if (variant.size != 2) {
                logger.warn { "Bad specifier $it" }
                return@forEach
            }

            result[variant[0]] = variant[1]
        }

        return result
    }

}

fun OFOddsChangeMarket.OFOutcome.hasAdditionalProbabilities(): Boolean {
    return winProbabilities != null && loseProbabilities != null && halfWinProbabilities != null && halfLoseProbabilities != null && refundProbabilities != null
}
