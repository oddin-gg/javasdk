package com.oddin.oddsfeedsdk

import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.mq.ChannelConsumer
import com.oddin.oddsfeedsdk.mq.ExchangeProviderImpl
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.util.concurrent.TimeUnit

// An exception thrown while filtering one message used to reach the RxJava
// onError handler, which disposes the subscription: the session then silently
// stopped receiving every later message while the AMQP connection stayed up.
// A bad message may be dropped, but the session must keep working.
class OddsFeedSessionResilienceTest {

    private fun feedMessage(): FeedMessage {
        val message = OFOddsChange()
        message.setProduct(1)
        val id = URN.parse("od:match:7")
        return FeedMessage(
            message,
            ByteArray(1),
            RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.7", null, id, false),
            MessageTimestamp(0, 0, 0, 0)
        )
    }

    @Test(timeout = 20_000)
    fun throwingFilterDropsTheMessageButKeepsTheSessionAlive() {
        val producer = mockk<Producer>(relaxed = true)
        var calls = 0
        val producerManager = mockk<SDKProducerManager>(relaxed = true) {
            every { getProducer(1L) } answers {
                calls++
                if (calls == 1) throw IllegalStateException("producer lookup failed") else producer
            }
            every { isProducerEnabled(1L) } returns true
        }
        val recoveryMessageProcessor = mockk<RecoveryMessageProcessor>(relaxed = true)
        val dispatchManager = DispatchManagerImpl()

        val session = OddsFeedSessionImpl(
            mockk<ChannelConsumer>(relaxed = true),
            dispatchManager,
            producerManager,
            mockk<CacheManager>(relaxed = true),
            mockk<FeedMessageFactory>(relaxed = true),
            recoveryMessageProcessor,
            ExchangeProviderImpl()
        )
        session.open(listOf("#"), MessageInterest.ALL, mockk<OddsFeedListener>(relaxed = true), null)
        // listen() subscribes on the dispatcher's own scheduler; give it a moment.
        TimeUnit.MILLISECONDS.sleep(500)

        dispatchManager.publish(feedMessage()) // filter throws -> dropped
        dispatchManager.publish(feedMessage()) // must still be processed

        verify(timeout = 5_000, exactly = 1) {
            recoveryMessageProcessor.onMessageProcessingStarted(any(), 1L, any())
        }
    }
}
