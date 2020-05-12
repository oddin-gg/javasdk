package com.oddin.oddsfeedsdk.mq.entities

import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.api.factories.FeedMessageMarket
import com.oddin.oddsfeedsdk.api.factories.MarketFactory
import com.oddin.oddsfeedsdk.cache.StaticData
import com.oddin.oddsfeedsdk.schema.feed.v1.*
import java.util.*

interface UnparsedMessage

interface BasicMessage : UnparsedMessage {
    fun getProduct(): Int
    fun getTimestamp(): Long
}

fun OFFixtureChange.key(): String {
    return "${getProduct()}_${eventId}_${getTimestamp()}"
}

data class MessageTimestamp(var created: Long, val sent: Long, val received: Long, var published: Long)

interface Message {
    val producer: Producer?
    val timestamp: MessageTimestamp
}

abstract class MessageImpl(override val producer: Producer?, override val timestamp: MessageTimestamp) : Message

interface EventMessage<T : SportEvent> : Message {
    val event: T
    val requestId: Long?
    val rawMessage: ByteArray
}

abstract class EventMessageImpl<T : SportEvent>(
    override val event: T,
    override val requestId: Long?,
    override val rawMessage: ByteArray,
    override val producer: Producer,
    override val timestamp: MessageTimestamp
) : EventMessage<T>

interface UnparsableMessage<T : SportEvent> : Message {
    val event: T
    val rawMessage: ByteArray?
}

class UnparsableMessageImpl<T : SportEvent>(
    timestamp: MessageTimestamp,
    override val event: T,
    override val rawMessage: ByteArray?
) : MessageImpl(null, timestamp), UnparsableMessage<T>

interface MarketMessage<T : SportEvent> : EventMessage<T> {
    val markets: List<Market>
}

interface MarketInitializer {
    fun <S : Market> initMarkets(
        markets: List<FeedMessageMarket>?,
        event: SportEvent,
        marketFactory: MarketFactory
    ): List<S> {
        return markets?.mapNotNull {
            marketFactory.buildMarket<S>(event, it)
        }?.toList()
            ?: emptyList()
    }
}

enum class OddsChangeReason {
    NORMAL, RISK_ADJUSTMENT, SYSTEM_DOWN
}

interface OddsChange<T : SportEvent> : MarketMessage<T> {
    val changeReason: OddsChangeReason
    val betStopReasonData: StaticData?
    val betStopReason: String?
    val bettingStatusData: StaticData?
    val bettingStatus: String?
    override val markets: List<MarketWithOdds>
}

class OddsChangeImpl<T : SportEvent>(
    event: T,
    private val message: OFOddsChange,
    rawMessage: ByteArray,
    producer: Producer,
    timestamp: MessageTimestamp,
    private val marketFactory: MarketFactory
) : EventMessageImpl<T>(event, message.requestId, rawMessage, producer, timestamp), OddsChange<T>, MarketInitializer {
    private var _markets: List<MarketWithOdds>? = null

    override val changeReason: OddsChangeReason
        get() {
            return when (message.oddsChangeReason) {
                OFOddsChangeReason.RISK_ADJUSTMENT_UPDATE -> OddsChangeReason.RISK_ADJUSTMENT
                else -> OddsChangeReason.NORMAL
            }
        }

    override val betStopReasonData: StaticData?
        get() = null

    override val betStopReason: String?
        get() = null

    override val bettingStatusData: StaticData?
        get() = null

    override val bettingStatus: String?
        get() = null

    override val markets: List<MarketWithOdds>
        get() {
            val markets = _markets ?: initMarkets(message.odds?.market, event, marketFactory)
            _markets = markets
            return markets
        }
}

interface BetStop<T : SportEvent> : EventMessage<T> {
    val groups: List<String>?
    val marketStatus: MarketStatus
}

class BetStopImpl<T : SportEvent>(
    event: T,
    private val message: OFBetStop,
    rawMessage: ByteArray,
    producer: Producer,
    timestamp: MessageTimestamp
) : EventMessageImpl<T>(event, message.requestId, rawMessage, producer, timestamp),
    BetStop<T> {

    override val groups: List<String>?
        get() = message.groups?.split("\\|")?.toList()

    override val marketStatus: MarketStatus
        get() = MarketStatus.fromFeedValue(message.marketStatus)

}

enum class BetSettlementCertainty {
    LIVE_SCOUTED, CONFIRMED, UNKNOWN
}

interface BetSettlement<T : SportEvent> : MarketMessage<T> {
    val certainty: BetSettlementCertainty
    override val markets: List<MarketWithSettlement>
}

class BetSettlementImpl<T : SportEvent>(
    event: T,
    private val message: OFBetSettlement,
    rawMessage: ByteArray,
    producer: Producer,
    timestamp: MessageTimestamp,
    private val marketFactory: MarketFactory
) : EventMessageImpl<T>(event, message.requestId, rawMessage, producer, timestamp), BetSettlement<T>,
    MarketInitializer {
    private var _markets: List<MarketWithSettlement>? = null

    override val certainty: BetSettlementCertainty
        get() {
            return when (message.certainty) {
                1 -> BetSettlementCertainty.LIVE_SCOUTED
                2 -> BetSettlementCertainty.CONFIRMED
                else -> BetSettlementCertainty.UNKNOWN
            }
        }

    override val markets: List<MarketWithSettlement>
        get() {
            val markets = _markets ?: initMarkets(message.outcomes?.market, event, marketFactory)
            _markets = markets
            return markets
        }
}

interface BetCancel<T : SportEvent> : MarketMessage<T> {
    val startTime: Date?
    val endTime: Date?
    val supercededBy: String?
    override val markets: List<MarketCancel>
}

class BetCancelImpl<T : SportEvent>(
    event: T,
    private val message: OFBetCancel,
    rawMessage: ByteArray,
    producer: Producer,
    timestamp: MessageTimestamp,
    private val marketFactory: MarketFactory
) : EventMessageImpl<T>(event, message.requestId, rawMessage, producer, timestamp), BetCancel<T>,
    MarketInitializer {
    private var _markets: List<MarketCancel>? = null

    override val markets: List<MarketCancel>
        get() {
            val markets = _markets ?: initMarkets(message.market, event, marketFactory)
            _markets = markets
            return markets
        }

    override val startTime: Date?
        get() {
            val time = message.startTime ?: return null
            return Date(time)
        }

    override val endTime: Date?
        get() {
            val time = message.endTime ?: return null
            return Date(time)
        }

    override val supercededBy: String?
        get() = message.supercededBy
}

enum class FixtureChangeType {
    NEW, TIME_UPDATE, CANCELLED, OTHER_CHANGE, COVERAGE;

    companion object {
        fun fromFeedType(fixtureChangeType: OFChangeType?): FixtureChangeType {
            return when (fixtureChangeType) {
                OFChangeType.NEW -> NEW
                OFChangeType.CANCELLED -> CANCELLED
                OFChangeType.DATETIME -> TIME_UPDATE
                OFChangeType.COVERAGE -> COVERAGE
                OFChangeType.FORMAT -> OTHER_CHANGE
                else -> OTHER_CHANGE
            }
        }
    }
}

interface FixtureChange<T : SportEvent> : EventMessage<T> {
    val changeType: FixtureChangeType
    val nextLiveTime: Date?
    val startTime: Date
}

class FixtureChangeImpl<T : SportEvent>(
    event: T,
    private val message: OFFixtureChange,
    rawMessage: ByteArray,
    producer: Producer,
    timestamp: MessageTimestamp
) : EventMessageImpl<T>(event, message.requestId, rawMessage, producer, timestamp), FixtureChange<T> {

    override val changeType: FixtureChangeType
        get() = FixtureChangeType.fromFeedType(message.changeType)

    override val nextLiveTime: Date?
        get() {
            val time = message.nextLiveTime ?: return null
            return Date(time)
        }

    override val startTime: Date
        get() = Date(message.startTime)

}

enum class ProducerStatusReason {
    FIRST_RECOVERY_COMPLETED,
    PROCESSING_QUEUE_DELAY_STABILIZED,
    RETURNED_FROM_INACTIVITY,
    ALIVE_INTERVAL_VIOLATION,
    PROCESSING_QUEUE_DELAY_VIOLATION,
    OTHER
}

enum class ProducerDownReason {
    ALIVE_INTERVAL_VIOLATION,
    PROCESSING_QUEUE_DELAY_VIOLATION,
    OTHER;

    fun toProducerStatusReason(): ProducerStatusReason {
        return when (this) {
            ALIVE_INTERVAL_VIOLATION -> ProducerStatusReason.ALIVE_INTERVAL_VIOLATION
            PROCESSING_QUEUE_DELAY_VIOLATION -> ProducerStatusReason.PROCESSING_QUEUE_DELAY_VIOLATION
            OTHER -> ProducerStatusReason.OTHER
        }
    }
}

enum class ProducerUpReason {
    FIRST_RECOVERY_COMPLETED,
    PROCESSING_QUEUE_DELAY_STABILIZED,
    RETURNED_FROM_INACTIVITY;

    fun toProducerStatusReason(): ProducerStatusReason {
        return when (this) {
            FIRST_RECOVERY_COMPLETED -> ProducerStatusReason.FIRST_RECOVERY_COMPLETED
            PROCESSING_QUEUE_DELAY_STABILIZED -> ProducerStatusReason.PROCESSING_QUEUE_DELAY_STABILIZED
            RETURNED_FROM_INACTIVITY -> ProducerStatusReason.RETURNED_FROM_INACTIVITY
        }
    }
}

enum class RecoveryState {
    NOT_STARTED, STARTED, COMPLETED, INTERRUPTED, ERROR
}

interface ProducerStatus : Message {
    val isDown: Boolean
    val isDelayed: Boolean
    val producerStatusReason: ProducerStatusReason
}

data class ProducerStatusImpl(
    override val producer: Producer?,
    override val timestamp: MessageTimestamp,
    override val isDown: Boolean,
    override val isDelayed: Boolean,
    override val producerStatusReason: ProducerStatusReason
) : ProducerStatus