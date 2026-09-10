package com.oddin.oddsfeedsdk

import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.mq.ChannelConsumer
import com.oddin.oddsfeedsdk.mq.ExchangeProviderImpl
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// One exception raised while filtering a message, or by a client callback, used
// to reach the RxJava onError handler, which disposes the subscription: the
// session then silently stopped delivering every later message of that kind
// while the AMQP connection stayed up. A bad message may be dropped, but the
// session must keep working.
class OddsFeedSessionResilienceTest {

    private val producerManager = mockk<SDKProducerManager>(relaxed = true)
    private val recoveryMessageProcessor = mockk<RecoveryMessageProcessor>(relaxed = true)
    private val listener = mockk<OddsFeedListener>(relaxed = true)
    private val extListener = mockk<OddsFeedExtListener>(relaxed = true)
    private val dispatchManager = DispatchManagerImpl()
    private var session: OddsFeedSessionImpl? = null

    @After
    fun tearDown() {
        session?.close()
        dispatchManager.close()
    }

    private fun feedMessage(product: Int): FeedMessage {
        val message = OFOddsChange()
        message.setProduct(product)
        val id = URN.parse("od:match:7")
        return FeedMessage(
            message,
            ByteArray(1),
            RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.7", null, id, false),
            MessageTimestamp(0, 0, 0, 0)
        )
    }

    private fun openSession(withExtListener: Boolean): OddsFeedSessionImpl {
        val session = OddsFeedSessionImpl(
            mockk<ChannelConsumer>(relaxed = true),
            dispatchManager,
            producerManager,
            mockk<CacheManager>(relaxed = true),
            mockk<FeedMessageFactory>(relaxed = true),
            recoveryMessageProcessor,
            ExchangeProviderImpl()
        )
        session.open(listOf("#"), MessageInterest.ALL, listener, if (withExtListener) extListener else null)
        this.session = session
        return session
    }

    // listen() subscribes on the dispatcher's own scheduler, so a message published
    // right after open() can be missed. Publish probes until one is observed.
    private fun publishUntilSeen(seen: CountDownLatch, probe: () -> Any) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (seen.count > 0 && System.nanoTime() < deadline) {
            dispatchManager.publish(probe())
            seen.await(50, TimeUnit.MILLISECONDS)
        }
        assertTrue("subscription never became active", seen.count == 0L)
    }

    @Test(timeout = 30_000)
    fun throwingFilterDropsTheMessageButKeepsTheSessionAlive() {
        val probeSeen = CountDownLatch(1)
        every { producerManager.getProducer(99L) } returns mockk<Producer>(relaxed = true)
        every { producerManager.isProducerEnabled(any()) } returns true
        every { recoveryMessageProcessor.onMessageProcessingStarted(any(), 99L, any()) } answers { probeSeen.countDown() }

        var calls = 0
        every { producerManager.getProducer(1L) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("producer lookup failed") else mockk<Producer>(relaxed = true)
        }

        openSession(withExtListener = false)
        publishUntilSeen(probeSeen) { feedMessage(99) }

        dispatchManager.publish(feedMessage(1)) // filter throws -> dropped
        dispatchManager.publish(feedMessage(1)) // must still be processed

        verify(timeout = 5_000, exactly = 1) {
            recoveryMessageProcessor.onMessageProcessingStarted(any(), 1L, any())
        }
    }

    @Test(timeout = 30_000)
    fun throwingUnparsableCallbackDoesNotStopLaterUnparsableMessages() {
        val probe = mockk<UnparsableMessage<SportEvent>>()
        val throwing = mockk<UnparsableMessage<SportEvent>>()
        val afterwards = mockk<UnparsableMessage<SportEvent>>()
        val probeSeen = CountDownLatch(1)
        val afterwardsSeen = CountDownLatch(1)
        every { listener.onUnparsableMessage(any(), probe) } answers { probeSeen.countDown() }
        every { listener.onUnparsableMessage(any(), throwing) } throws RuntimeException("client callback failed")
        every { listener.onUnparsableMessage(any(), afterwards) } answers { afterwardsSeen.countDown() }

        openSession(withExtListener = false)
        publishUntilSeen(probeSeen) { probe }

        dispatchManager.publish(throwing)
        dispatchManager.publish(afterwards)

        assertTrue("later unparsable message was not delivered", afterwardsSeen.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 30_000)
    fun throwingRawCallbackDoesNotStopLaterRawMessages() {
        fun raw(message: UnparsedMessage) = RawFeedMessage(
            message, ByteArray(1), MessageInterest.ALL,
            RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.7", null, URN.parse("od:match:7"), false),
            MessageTimestamp(0, 0, 0, 0)
        )
        val probe = mockk<UnparsedMessage>()
        val throwing = mockk<UnparsedMessage>()
        val afterwards = mockk<UnparsedMessage>()
        val probeSeen = CountDownLatch(1)
        val afterwardsSeen = CountDownLatch(1)
        every { extListener.onRawFeedMessageReceived(probe, any(), any(), any()) } answers { probeSeen.countDown() }
        every { extListener.onRawFeedMessageReceived(throwing, any(), any(), any()) } throws RuntimeException("client callback failed")
        every { extListener.onRawFeedMessageReceived(afterwards, any(), any(), any()) } answers { afterwardsSeen.countDown() }

        openSession(withExtListener = true)
        publishUntilSeen(probeSeen) { raw(probe) }

        dispatchManager.publish(raw(throwing))
        dispatchManager.publish(raw(afterwards))

        assertTrue("later raw message was not delivered", afterwardsSeen.await(5, TimeUnit.SECONDS))
    }
}
