package com.oddin.oddsfeedsdk

import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.name.Named
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.mq.ChannelConsumer
import com.oddin.oddsfeedsdk.mq.ExchangeNameProvider
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.entities.*
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSnapshotComplete
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener
import io.reactivex.disposables.Disposable
import mu.KotlinLogging
import java.util.*

interface OddsFeedSession

interface ReplaySession

interface SDKOddsFeedSession {
    fun open(
        routingKeys: List<String>,
        messageInterest: MessageInterest,
        oddsFeedListener: OddsFeedListener?,
        oddsFeedExtListener: OddsFeedExtListener?
    )

    fun close()
}

private val logger = KotlinLogging.logger {}

open class OddsFeedSessionImpl @Inject constructor(
    private val channelConsumer: ChannelConsumer,
    private val dispatchManager: DispatchManager,
    private val producerManager: SDKProducerManager,
    private val cacheManager: CacheManager,
    private val feedMessageFactory: FeedMessageFactory,
    private val recoveryMessageProcessor: RecoveryMessageProcessor,
    private val exchangeNameProvider: ExchangeNameProvider
) : OddsFeedSession, SDKOddsFeedSession {

    private val sessionId: UUID = UUID.randomUUID()
    private val subscriptions = mutableListOf<Disposable>()

    override fun open(
        routingKeys: List<String>,
        messageInterest: MessageInterest,
        oddsFeedListener: OddsFeedListener?,
        oddsFeedExtListener: OddsFeedExtListener?
    ) {
        val feedMessageDisposable = dispatchManager
            .listen(FeedMessage::class.java)
            .filter {
                filterFeedMessage(it, messageInterest)
            }
            .filter {
                filterFixtureChanges(it)
            }
            .subscribe({
                try {
                    feedMessageReceived(it, messageInterest, oddsFeedListener)
                } catch (e: Exception) {
                    logger.error { "Failed to process message ${it.message}" }
                    publishUnparsableMessage(it)
                }
            }, {
                logger.error("Failed to process message with - $it")
            })
        subscriptions.add(feedMessageDisposable)

        val unparsableMessageDisposable = dispatchManager.listen(UnparsableMessage::class.java)
            .subscribe({
                @Suppress("UNCHECKED_CAST") val message = it as? UnparsableMessage<SportEvent> ?: return@subscribe
                oddsFeedListener?.onUnparsableMessage(
                    this@OddsFeedSessionImpl,
                    message
                )
            }, {
                logger.error { "Exception during unparsable message client processing - $it" }
            })
        subscriptions.add(unparsableMessageDisposable)

        if (oddsFeedExtListener != null) {
            logger.info { "Adding extended listener to session $sessionId" }
            val rawMessageDisposable = dispatchManager
                .listen(RawFeedMessage::class.java)
                .subscribe({
                    oddsFeedExtListener.onRawFeedMessageReceived(
                        it.message,
                        it.messageInterest,
                        it.routingKey,
                        it.timestamp
                    )
                }, {
                    logger.error { "Exception during raw message client processing - $it" }
                })
            subscriptions.add(rawMessageDisposable)
        } else {
            logger.info { "No extended listener for session $sessionId" }
        }

        // Start connection to AMQP
        channelConsumer.open(routingKeys, messageInterest, dispatchManager, exchangeNameProvider)

        logger.info { "Session opened with message interest - $messageInterest" }
    }

    override fun close() {
        subscriptions.forEach { it.dispose() }
        subscriptions.clear()
        dispatchManager.close()
        channelConsumer.close()
    }

    // Filter messages based on producer scope, state and type of message
    private fun filterFeedMessage(feedMessage: FeedMessage, messageInterest: MessageInterest): Boolean {
        val producerId = feedMessage.message?.getProduct()?.toLong() ?: return false
        // @TODO unify long/int ?
        val producer = producerManager.getProducer(producerId)
        if (producer == null) {
            logger.debug { "Unknown producer $producerId sending message - ${feedMessage.message}" }
            return false
        }

        val isProducerEnabled = producerManager.isProducerEnabled(producerId)
        if (!isProducerEnabled) {
            return false
        }

        return messageInterest.isProducerInScope(producer)
    }

    // Simple filter for odds change messages
    private fun filterFixtureChanges(feedMessage: FeedMessage): Boolean {
        return if (feedMessage.message is OFFixtureChange) {
            cacheManager.dispatchedFixtureChanges.getIfPresent(feedMessage.message.key()) == null
        } else {
            true
        }
    }

    private fun feedMessageReceived(
        feedMessage: FeedMessage,
        messageInterest: MessageInterest,
        oddsFeedListener: OddsFeedListener?
    ) {
        if (logger.isDebugEnabled && feedMessage.rawMessage != null) {
            logger.debug { "xml feed message received: ${String(feedMessage.rawMessage)}" }
        }

        val producerId = feedMessage.message?.getProduct()?.toLong() ?: return
        recoveryMessageProcessor.onMessageProcessingStarted(sessionId, producerId, System.currentTimeMillis())

        cacheManager.onFeedMessageReceived(sessionId, feedMessage)

        var timestamp: Long? = null
        when (feedMessage.message) {
            is OFFixtureChange -> {
                val messageKey = feedMessage.message.key()
                cacheManager.dispatchedFixtureChanges.put(messageKey, messageKey)
            }

            is OFSnapshotComplete -> {
                recoveryMessageProcessor.onSnapshotCompleteReceived(
                    producerId,
                    feedMessage.timestamp,
                    feedMessage.message.requestId,
                    messageInterest
                )
            }

            is OFAlive -> {
                timestamp = feedMessage.timestamp.created
                recoveryMessageProcessor.onAliveReceived(
                    producerId,
                    feedMessage.timestamp,
                    feedMessage.message.subscribed == 1,
                    messageInterest
                )
            }
        }

        if (feedMessage.message !is OFAlive && feedMessage.message !is OFSnapshotComplete) {
            when (val message = feedMessageFactory.buildMessage<SportEvent>(feedMessage)) {
                is BetStop<SportEvent> -> {
                    timestamp = message.timestamp.created
                    oddsFeedListener?.onBetStop(this@OddsFeedSessionImpl, message)
                }
                is BetCancel<SportEvent> -> {
                    oddsFeedListener?.onBetCancel(this@OddsFeedSessionImpl, message)
                }
                is BetSettlement<SportEvent> -> {
                    oddsFeedListener?.onBetSettlement(this@OddsFeedSessionImpl, message)
                }
                is RollbackBetSettlement<SportEvent> -> {
                    oddsFeedListener?.onRollbackBetSettlement(this@OddsFeedSessionImpl, message)
                }
                is RollbackBetCancel<SportEvent> -> {
                    oddsFeedListener?.onRollbackBetCancel(this@OddsFeedSessionImpl, message)
                }
                is FixtureChange<SportEvent> -> {
                    oddsFeedListener?.onFixtureChange(this@OddsFeedSessionImpl, message)
                }
                is OddsChange<SportEvent> -> {
                    timestamp = message.timestamp.created
                    oddsFeedListener?.onOddsChange(this@OddsFeedSessionImpl, message)
                }
                else -> publishUnparsableMessage(feedMessage)
            }
        }

        recoveryMessageProcessor.onMessageProcessingEnded(sessionId, producerId, timestamp)
    }

    private fun publishUnparsableMessage(feedMessage: FeedMessage) {
        try {
            val message = feedMessageFactory.buildUnparsableMessage<SportEvent>(feedMessage)
            dispatchManager.publish(message)
        } catch (e: Exception) {
            logger.error { "Failed to publish unparsable message $e" }
        }
    }
}

interface OddsFeedSessionBuilder {
    fun setListener(listener: OddsFeedListener): OddsFeedSessionBuilder

    fun setMessageInterest(messageInterest: MessageInterest): OddsFeedSessionBuilder

    fun setSpecificEventsOnly(specificEvents: Set<URN>): OddsFeedSessionBuilder

    fun setSpecificEventOnly(specificEventOnly: URN): OddsFeedSessionBuilder

    fun build(): OddsFeedSession

    fun buildReplay(): ReplaySession
}

data class SessionData(
    val oddsFeedSession: SDKOddsFeedSession,
    val messageInterest: MessageInterest,
    val eventIds: Set<URN>,
    val oddsFeedListener: OddsFeedListener?
) {
    val id = UUID.randomUUID().toString()
}

class OddsFeedSessionBuilderImpl(
    private val sessions: MutableSet<SessionData>,
    private val injector: Injector
) : OddsFeedSessionBuilder {

    private var oddsFeedListener: OddsFeedListener? = null
    private var messageInterest: MessageInterest? = null
    private var eventIds: MutableSet<URN>? = null

    override fun setListener(listener: OddsFeedListener): OddsFeedSessionBuilder {
        oddsFeedListener = listener
        return this
    }

    override fun setMessageInterest(messageInterest: MessageInterest): OddsFeedSessionBuilder {
        this.messageInterest = messageInterest
        return this
    }

    override fun setSpecificEventsOnly(specificEvents: Set<URN>): OddsFeedSessionBuilder {
        messageInterest = MessageInterest.SPECIFIED_MATCHES_ONLY
        val eventIds = this.eventIds ?: mutableSetOf()
        eventIds.addAll(specificEvents)

        this.eventIds = eventIds
        return this
    }

    override fun setSpecificEventOnly(specificEventOnly: URN): OddsFeedSessionBuilder {
        return setSpecificEventsOnly(setOf(specificEventOnly))
    }

    override fun build(): OddsFeedSession {
        val messageInterest = messageInterest ?: throw IllegalArgumentException("Message interest not specified")
        val oddsFeedListener = oddsFeedListener ?: throw IllegalArgumentException("Listener not specified")
        val session = injector.getInstance(OddsFeedSessionImpl::class.java)
        sessions.add(SessionData(session, messageInterest, eventIds ?: setOf(), oddsFeedListener))

        this.messageInterest = null
        eventIds = null

        return session
    }

    override fun buildReplay(): ReplaySession {
        val oddsFeedListener = oddsFeedListener ?: throw IllegalArgumentException("Listener not specified")
        val session = injector.getInstance(ReplaySessionImpl::class.java)
        sessions.add(SessionData(session, MessageInterest.ALL, setOf(), oddsFeedListener))

        return session
    }
}

class ReplaySessionImpl @Inject constructor(
    channelConsumer: ChannelConsumer,
    dispatchManager: DispatchManager,
    producerManager: SDKProducerManager,
    cacheManager: CacheManager,
    feedMessageFactory: FeedMessageFactory,
    @Named("DummyRecoveryMessageProcessor")
    recoveryMessageProcessor: RecoveryMessageProcessor,
    @Named("ReplayExchange")
    exchangeNameProvider: ExchangeNameProvider
) : ReplaySession, OddsFeedSessionImpl(
    channelConsumer,
    dispatchManager,
    producerManager,
    cacheManager,
    feedMessageFactory,
    recoveryMessageProcessor,
    exchangeNameProvider
)
