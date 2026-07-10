package com.oddin.oddsfeedsdk.mq

import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.mq.rabbit.AMQPConnectionProvider
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.Envelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CORE-3811: the consumer runs with autoAck, so an exception escaping
// handleDelivery is unrecoverable — neither a malformed routing key nor a
// failing unparsable-message build may ever rethrow.
class ChannelConsumerTest {

    private val consumerSlot = slot<Consumer>()
    private val published = mutableListOf<Any>()

    private fun openConsumer(feedMessageFactory: FeedMessageFactory) {
        val channel = mockk<Channel>(relaxed = true) {
            every { queueDeclare() } returns mockk(relaxed = true) {
                every { queue } returns "test-queue"
            }
            every { basicConsume(any<String>(), any<Boolean>(), capture(consumerSlot)) } returns "tag"
        }
        val channelProvider = mockk<AMQPConnectionProvider> {
            every { newChannel() } returns channel
        }
        val dispatchManager = mockk<DispatchManager> {
            every { publish(capture(published)) } returns Unit
        }
        ChannelConsumerImpl(channelProvider, feedMessageFactory)
            .open(listOf("#"), MessageInterest.ALL, dispatchManager, ExchangeProviderImpl())
    }

    private fun deliver(routingKey: String, body: ByteArray) {
        consumerSlot.captured.handleDelivery(
            "tag",
            Envelope(1L, false, "oddinfeed", routingKey),
            AMQP.BasicProperties.Builder().build(),
            body
        )
    }

    @Test
    fun brokenMessageDoesNotThrowIntoTheAmqpConsumerThread() {
        val feedMessageFactory = mockk<FeedMessageFactory> {
            every { buildUnparsableMessage<SportEvent>(any()) } throws
                IllegalArgumentException("Required value was null.")
        }
        openConsumer(feedMessageFactory)

        // Empty body + a routing key the regex cannot match at all: parseRoute must
        // fall back to a system routing-key info (not throw), and the failing
        // unparsable build must be swallowed. Before the fix both escaped
        // handleDelivery into the RabbitMQ consumer thread.
        deliver("garbage", ByteArray(0))
    }

    @Test
    fun blankSportSlotParsesToNullSportWithEventPresent() {
        openConsumer(mockk(relaxed = true))

        deliver("hi.pre.-.odds_change.-.od:match.2886418.-", "<odds_change/>".toByteArray())

        val feedMessage = published.filterIsInstance<FeedMessage>().single()
        assertNull(feedMessage.routingKey.sportId)
        assertEquals("od:match:2886418", feedMessage.routingKey.eventId?.toString())
    }
}
