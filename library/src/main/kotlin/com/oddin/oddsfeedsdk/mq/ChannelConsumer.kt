package com.oddin.oddsfeedsdk.mq

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.RawFeedMessage
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage
import com.oddin.oddsfeedsdk.mq.rabbit.AMQPConnectionProvider
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.regex.Pattern
import javax.xml.bind.JAXBContext
import javax.xml.bind.JAXBException
import javax.xml.bind.Unmarshaller

interface ExchangeNameProvider {
    fun exchangeName(): String
}

class ReplayExchangeProviderImpl @Inject constructor(): ExchangeNameProvider {
    override fun exchangeName(): String {
        return "oddinreplay"
    }
}

class ExchangeProviderImpl @Inject constructor(): ExchangeNameProvider {
    override fun exchangeName(): String {
        return "oddinfeed"
    }
}

interface ChannelConsumer {
    fun open(
        routingKeys: List<String>,
        messageInterest: MessageInterest,
        dispatchManager: DispatchManager,
        exchangeNameProvider: ExchangeNameProvider
    )

    fun close()
    val isOpened: Boolean
}

private val logger = KotlinLogging.logger {}

class ChannelConsumerImpl @Inject constructor(
    private val channelProvider: AMQPConnectionProvider,
    private val feedMessageFactory: FeedMessageFactory
) : ChannelConsumer {

    private var _isOpened = false
    override val isOpened: Boolean
        get() = _isOpened

    private var channel: Channel? = null

    private var unmarshaller: Unmarshaller

    companion object {
        private const val TIMESTAMP_KEY = "timestamp_in_ms"
        private const val SPORT_NAME = "sportId"
        private const val EVENT_TYPE_NAME = "eventType"
        private const val EVENT_ID_NAME = "eventId"
        private val REGEX_PATTERN =
            Pattern.compile("\\A([^.]+)\\.([^.]+)\\.([^.]+)\\.([^.]+)\\.(?<sportId>((\\d+)|(-)))\\.(?<eventType>((([a-z]+):([a-zA-Z_2]+))|(-)))\\.(?<eventId>((\\d+)|(-)))(\\.(?<nodeId>((-?\\d+)|(-))))?(\\z)")
        private const val SPORT_ID_PREFIX = "od:sport:"
        private const val EMPTY_POSITION = "-"

    }

    init {
        val jc: JAXBContext = JAXBContext.newInstance(
            "com.oddin.oddsfeedsdk.schema.feed.v1"
        )
        unmarshaller = jc.createUnmarshaller()
    }

    override fun open(
        routingKeys: List<String>,
        messageInterest: MessageInterest,
        dispatchManager: DispatchManager,
        exchangeNameProvider: ExchangeNameProvider
    ) {
        if (isOpened) {
            return
        }

        val channel = channelProvider.newChannel()
        val queueName = channel.queueDeclare()?.queue
        routingKeys.forEach {
            logger.debug { "Binding queue $queueName with routing key $it" }
            channel.queueBind(
                queueName,
                exchangeNameProvider.exchangeName(),
                it
            )
        }

        val consumer = object : DefaultConsumer(channel) {
            override fun handleDelivery(
                consumerTag: String,
                envelope: Envelope,
                properties: AMQP.BasicProperties,
                body: ByteArray
            ) {
                logger.debug { body.toString(Charset.defaultCharset()) }
                publishMessage(
                    envelope.routingKey,
                    body,
                    properties,
                    System.currentTimeMillis(),
                    messageInterest,
                    dispatchManager
                )
            }
        }

        channel.basicConsume(queueName, true, consumer)
        this.channel = channel
        _isOpened = true
    }

    override fun close() {
        try {
            channel?.close()
        } catch (e: Exception) {
            logger.warn { "Channel closed failed" }
        }
    }

    private fun publishMessage(
        route: String,
        body: ByteArray?,
        properties: AMQP.BasicProperties?,
        receivedAt: Long,
        messageInterest: MessageInterest,
        dispatchManager: DispatchManager
    ) {
        val sentAt = properties?.headers?.get(TIMESTAMP_KEY)?.toString()?.toLong() ?: 0
        val timestamp = MessageTimestamp(0, sentAt, receivedAt, 0)
        val routingKeyInfo = parseRoute(route)

        if (body == null || body.isEmpty()) {
            logger.warn { "Received message without proper body from $route" }
            publishUnparsableMessage(FeedMessage(null, body, routingKeyInfo, timestamp), dispatchManager)
            return
        }

        val unparsedMessage: UnparsedMessage
        try {
            unparsedMessage = unmarshaller.unmarshal(ByteArrayInputStream(body)) as UnparsedMessage
        } catch (e: JAXBException) {
            publishUnparsableMessage(FeedMessage(null, body, routingKeyInfo, timestamp), dispatchManager)
            return
        }

        val rawFeedMessage = RawFeedMessage(unparsedMessage, body, messageInterest, routingKeyInfo, timestamp)
        dispatchManager.publish(rawFeedMessage)

        val feedMessage = FeedMessage(unparsedMessage as? BasicMessage, body, routingKeyInfo, timestamp)
        dispatchManager.publish(feedMessage)
    }

    private fun parseRoute(route: String): RoutingKeyInfo {
        val matcher = REGEX_PATTERN.matcher(route)
        val find = matcher.find()
        val sportId = matcher.group(SPORT_NAME)
        val eventId = matcher.group(EVENT_ID_NAME)
        val hasId = sportId != EMPTY_POSITION || eventId != EMPTY_POSITION

        return if (find && hasId) {
            val sportIdUrn = if (sportId != EMPTY_POSITION) {
                try {
                    URN.parse("$SPORT_ID_PREFIX${matcher.group("sportId")}")
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val eventType = matcher.group(EVENT_TYPE_NAME)
            val eventIdUrn = if (eventType != EMPTY_POSITION && eventId != EMPTY_POSITION) {
                try {
                    URN.parse("$eventType:$eventId")
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            RoutingKeyInfo(route, sportIdUrn, eventIdUrn, false)
        } else {
            RoutingKeyInfo(route, null, null, true)
        }
    }

    private fun publishUnparsableMessage(feedMessage: FeedMessage, dispatchManager: DispatchManager) {
        val message = feedMessageFactory.buildUnparsableMessage<SportEvent>(feedMessage)
        dispatchManager.publish(message)
    }
}