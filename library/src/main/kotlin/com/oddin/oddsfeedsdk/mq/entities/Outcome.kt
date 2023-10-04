package com.oddin.oddsfeedsdk.mq.entities

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor
import com.oddin.oddsfeedsdk.api.factories.MarketData
import com.oddin.oddsfeedsdk.api.entities.sportevent.HomeAway
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOutcomeActive
import com.oddin.oddsfeedsdk.schema.feed.v1.OFResult
import java.math.BigDecimal
import java.math.MathContext
import java.util.*

interface Outcome {
    val id: String
    val refId: Long?
    val name: String?
    fun getName(locale: Locale): String?
}

open class OutcomeImpl(
    override val id: String,
    override val refId: Long?,
    private val marketData: MarketData,
    private val locale: Locale
) : Outcome {

    override val name: String?
        get() = marketData.getOutcomeName(id, locale)

    override fun getName(locale: Locale): String? {
        return marketData.getOutcomeName(id, locale)
    }
}

interface OutcomeProbabilities : Outcome {
    val isActive: Boolean
    val probability: Double?
}

open class OutcomeProbabilitiesImpl(
    override val probability: Double?,
    private val active: OFOutcomeActive?,
    id: String,
    refId: Long?,
    marketData: MarketData,
    locale: Locale
) : OutcomeImpl(id, refId, marketData, locale), OutcomeProbabilities {
    override val isActive: Boolean
        get() = active == null || active == OFOutcomeActive.ACTIVE
}

enum class OddsDisplayType {
    DECIMAL, AMERICAN
}

interface AdditionalProbabilities {
    val win: Double
    val lose: Double
    val halfWin: Double
    val halfLose: Double
    val refund: Double
}

data class AdditionalProbabilitiesImpl(
    override val win: Double,
    override val lose: Double,
    override val halfWin: Double,
    override val halfLose: Double,
    override val refund: Double
) : AdditionalProbabilities

interface OutcomeOdds : OutcomeProbabilities {
    val isPlayerOutcome: Boolean

    fun getOdds(oddsDisplayType: OddsDisplayType): Double?
    val additionalProbabilities: AdditionalProbabilities?
}

open class OutcomeOddsImpl(
    private val odds: Double?,
    probability: Double?,
    active: OFOutcomeActive?,
    id: String,
    refId: Long?,
    marketData: MarketData,
    locale: Locale,
    override val additionalProbabilities: AdditionalProbabilities?
) : OutcomeProbabilitiesImpl(probability, active, id, refId, marketData, locale), OutcomeOdds {

    override val isPlayerOutcome: Boolean
        get() = false

    override fun getOdds(oddsDisplayType: OddsDisplayType): Double? {
        return when (oddsDisplayType) {
            OddsDisplayType.DECIMAL -> odds
            OddsDisplayType.AMERICAN -> convertOdds(odds)
        }
    }

    private fun convertOdds(odds: Double?): Double? {
        if (odds == null || odds.isNaN()) {
            return odds
        }

        val bigOdds = BigDecimal.valueOf(odds)
        return when {
            bigOdds.toDouble() == 1.0 -> {
                null
            }
            bigOdds.toDouble() >= 2.0 -> {
                bigOdds.subtract(BigDecimal.valueOf(1.0).multiply(BigDecimal.valueOf(100L))).toDouble()
            }
            else -> {
                BigDecimal.valueOf(-100.0).divide(bigOdds.subtract(BigDecimal.valueOf(1L)), MathContext.DECIMAL128)
                    .toDouble()
            }
        }
    }
}

interface CompetitorOutcomeOdds : OutcomeOdds {
    val homeOrAwayTeam: HomeAway
    val team: Competitor?
}

class CompetitorOutcomeOddsImpl(
    private val match: Match,
    private val teamId: Int,
    odds: Double?,
    probability: Double?,
    active: OFOutcomeActive?,
    id: String,
    refId: Long?,
    marketData: MarketData,
    locale: Locale,
    additionalProbabilities: AdditionalProbabilities?
) : OutcomeOddsImpl(odds, probability, active, id, refId, marketData, locale, additionalProbabilities),
    CompetitorOutcomeOdds {

    override val homeOrAwayTeam: HomeAway
        get() = if (HomeAway.HOME.value == teamId) HomeAway.HOME else HomeAway.AWAY

    override val team: Competitor?
        get() {
            return when (homeOrAwayTeam) {
                HomeAway.HOME -> match.homeCompetitor
                HomeAway.AWAY -> match.awayCompetitor
            }
        }

    override val isPlayerOutcome: Boolean
        get() = true
}

enum class OutcomeResult {
    LOST, WON, UNDECIDED_YET
}

enum class VoidFactor(val value: Double) {
    REFUND_HALF(0.50),
    REFUND_FULL(1.00)
}

interface OutcomeSettlement : Outcome {
    val voidFactor: VoidFactor?
    val deadHeatFactor: Double?
    val outcomeResult: OutcomeResult
}

class OutcomeSettlementImpl(
    id: String,
    refId: Long?,
    marketData: MarketData,
    locale: Locale,
    private val void: Double?,
    override val deadHeatFactor: Double?,
    private val result: OFResult
) : OutcomeImpl(id, refId, marketData, locale), OutcomeSettlement {

    override val outcomeResult: OutcomeResult
        get() {
            return when (result) {
                OFResult.LOST -> OutcomeResult.LOST
                OFResult.UNDECIDED_YET -> OutcomeResult.UNDECIDED_YET
                OFResult.WON -> OutcomeResult.WON
            }
        }

    override val voidFactor: VoidFactor?
        get() {
            return when (void) {
                0.50 -> VoidFactor.REFUND_HALF
                1.00 -> VoidFactor.REFUND_FULL
                else -> null
            }
        }
}