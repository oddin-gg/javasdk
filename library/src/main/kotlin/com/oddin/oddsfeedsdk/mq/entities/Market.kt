package com.oddin.oddsfeedsdk.mq.entities

import com.oddin.oddsfeedsdk.api.factories.MarketData
import com.oddin.oddsfeedsdk.cache.StaticData
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFavourite
import com.oddin.oddsfeedsdk.schema.feed.v1.OFMarketStatus
import java.util.*


// @TODO Market description!
interface Market {
    val id: Int
    val refId: Int?
    val specifiers: Map<String, String>
    val name: String?

    fun getName(locale: Locale): String?
}

open class MarketImpl(
    override val id: Int,
    override val refId: Int?,
    override val specifiers: Map<String, String>,
    private val marketData: MarketData,
    private val locale: Locale
) : Market {

    override val name: String?
        get() = getName(locale)

    override fun getName(locale: Locale): String? {
        return marketData.getMarketName(locale)
    }
}

enum class MarketStatus {
    ACTIVE, SUSPENDED, DEACTIVATED, SETTLED, CANCELLED, HANDED_OVER;

    companion object {
        fun fromFeedValue(status: OFMarketStatus): MarketStatus {
            return when (status) {
                OFMarketStatus.ACTIVE -> ACTIVE
                OFMarketStatus.DEACTIVATED -> DEACTIVATED
                OFMarketStatus.SUSPENDED -> SUSPENDED
                OFMarketStatus.HANDED_OVER -> HANDED_OVER
                OFMarketStatus.SETTLED -> SETTLED
                OFMarketStatus.CANCELLED -> CANCELLED
            }
        }
    }
}

interface MarketWithOdds : Market {
    val status: MarketStatus
    val outcomeOdds: List<OutcomeOdds>
    val isFavourite: Boolean
}

class MarketWithOddsImpl(
    id: Int,
    refId: Int?,
    specifiers: Map<String, String>,
    marketData: MarketData,
    locale: Locale,
    override val outcomeOdds: List<OutcomeOdds>,
    private val feedMarketStatus: OFMarketStatus,
    private val favourite: OFFavourite?
) : MarketImpl(id, refId, specifiers, marketData, locale), MarketWithOdds {

    override val status: MarketStatus
        get() = MarketStatus.fromFeedValue(feedMarketStatus)

    override val isFavourite: Boolean
        get() = favourite != null && favourite == OFFavourite.YES
}

interface MarketWithSettlement : Market {
    val outcomeSettlements: List<OutcomeSettlement>
    val voidReasonValue: StaticData?
    val voidReason: String?
}

class MarketWithSettlementImpl(
    id: Int,
    refId: Int?,
    specifiers: Map<String, String>,
    marketData: MarketData,
    locale: Locale,
    override val outcomeSettlements: List<OutcomeSettlement>
) : MarketImpl(id, refId, specifiers, marketData, locale), MarketWithSettlement {

    override val voidReasonValue: StaticData?
        get() = null

    override val voidReason: String?
        get() = null
}

interface MarketCancel : Market {
    @Deprecated("voidReasonId and voidReasonParams")
    val voidReasonValue: StaticData?
    @Deprecated("voidReasonId and voidReasonParams")
    val voidReason: String?
    val voidReasonId: Int?
    val voidReasonParams: String?
}

class MarketCancelImpl(
    id: Int,
    refId: Int?,
    specifiers: Map<String, String>,
    marketData: MarketData,
    override val voidReasonId: Int?,
    override val voidReasonParams: String?,
    locale: Locale
): MarketImpl(id, refId, specifiers, marketData, locale), MarketCancel {
    override val voidReasonValue: StaticData?
        get() = null

    override val voidReason: String?
        get() = null
}