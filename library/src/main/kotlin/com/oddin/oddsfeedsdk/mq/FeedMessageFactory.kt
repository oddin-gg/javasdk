package com.oddin.oddsfeedsdk.mq

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.ProducerManager
import com.oddin.oddsfeedsdk.SDKProducerManager
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.api.factories.MarketFactory
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.mq.entities.*
import com.oddin.oddsfeedsdk.schema.feed.v1.*

interface FeedMessageFactory {

    fun <T : SportEvent> buildMessage(
        feedMessage: FeedMessage
    ): EventMessage<T>?

    fun <T : SportEvent> buildUnparsableMessage(
        feedMessage: FeedMessage
    ): UnparsableMessage<T>

    fun buildProducerStatus(
        producerId: Long,
        producerStatusReason: ProducerStatusReason,
        isDown: Boolean,
        isDelayed: Boolean,
        timestamp: Long
    ): ProducerStatus
}

class FeedMessageFactoryImpl @Inject constructor(
    private val entityFactory: EntityFactory,
    private val marketFactory: MarketFactory,
    private val producerManager: SDKProducerManager,
    private val config: OddsFeedConfiguration
) : FeedMessageFactory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : SportEvent> buildMessage(feedMessage: FeedMessage): EventMessage<T>? {
        requireNotNull(feedMessage.routingKey.eventId)
        requireNotNull(feedMessage.routingKey.sportId)
        requireNotNull(feedMessage.rawMessage)
        requireNotNull(feedMessage.message)

        val timestamp = feedMessage.timestamp.copy(published = System.currentTimeMillis())
        val sportEvent = entityFactory.buildMatch(
            feedMessage.routingKey.eventId,
            listOf(config.defaultLocale),
            sportId = feedMessage.routingKey.sportId
        ) as T
        val producer = producerManager.getProducer(feedMessage.message.getProduct().toLong())
        requireNotNull(producer)

        return when (feedMessage.message) {
            is OFOddsChange -> OddsChangeImpl(
                sportEvent,
                feedMessage.message,
                feedMessage.rawMessage,
                producer,
                timestamp,
                marketFactory
            )
            is OFBetStop -> BetStopImpl(
                sportEvent,
                feedMessage.message,
                feedMessage.rawMessage,
                producer,
                timestamp
            )
            is OFBetSettlement -> BetSettlementImpl(
                sportEvent,
                feedMessage.message,
                feedMessage.rawMessage,
                producer,
                timestamp,
                marketFactory
            )
            is OFBetCancel -> BetCancelImpl(
                sportEvent,
                feedMessage.message,
                feedMessage.rawMessage,
                producer,
                timestamp,
                marketFactory
            )
            is OFFixtureChange -> FixtureChangeImpl(
                sportEvent,
                feedMessage.message,
                feedMessage.rawMessage,
                producer,
                timestamp
            )
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : SportEvent> buildUnparsableMessage(
        feedMessage: FeedMessage
    ): UnparsableMessage<T> {
        requireNotNull(feedMessage.routingKey.eventId)
        requireNotNull(feedMessage.routingKey.sportId)
        val timestamp = feedMessage.timestamp.copy(published = System.currentTimeMillis())
        val sportEvent = entityFactory.buildMatch(
            feedMessage.routingKey.eventId,
            listOf(config.defaultLocale),
            sportId = feedMessage.routingKey.sportId
        ) as T

        return UnparsableMessageImpl(timestamp, sportEvent, feedMessage.rawMessage)
    }

    override fun buildProducerStatus(
        producerId: Long,
        producerStatusReason: ProducerStatusReason,
        isDown: Boolean,
        isDelayed: Boolean,
        timestamp: Long
    ): ProducerStatus {
        return ProducerStatusImpl(
            producerManager.getProducer(producerId),
            MessageTimestamp(timestamp, timestamp, timestamp, timestamp),
            isDown,
            isDelayed,
            producerStatusReason
        )
    }


}