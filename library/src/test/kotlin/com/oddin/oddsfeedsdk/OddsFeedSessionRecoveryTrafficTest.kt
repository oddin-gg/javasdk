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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The recovery manager gives a recovery up when nothing of it arrives for five minutes,
// so a session must tell it of every message, with the message's request id.
class OddsFeedSessionRecoveryTrafficTest {

    private val producerManager = mockk<SDKProducerManager>(relaxed = true)
    private val recoveryManager = mockk<RecoveryManagerImpl>(relaxed = true)
    private val dispatchManager = DispatchManagerImpl()
    private var session: OddsFeedSessionImpl? = null

    @After
    fun tearDown() {
        session?.close()
        dispatchManager.close()
    }

    private fun oddsChange(product: Int, requestId: Long?): FeedMessage {
        val message = OFOddsChange()
        message.setProduct(product)
        message.setTimestamp(1234L)
        message.setRequestId(requestId)
        return FeedMessage(
            message,
            ByteArray(1),
            RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.7", null, URN.parse("od:match:7"), false),
            MessageTimestamp(0, 0, 0, 0)
        )
    }

    @Test(timeout = 30_000)
    fun everyMessageReachesTheRecoveryManagerWithItsRequestId() {
        val probeSeen = CountDownLatch(1)
        every { producerManager.getProducer(any()) } returns mockk<Producer>(relaxed = true)
        every { producerManager.isProducerEnabled(any()) } returns true
        every { recoveryManager.onMessageProcessingStarted(any(), 99L, any()) } answers { probeSeen.countDown() }

        val session = OddsFeedSessionImpl(
            mockk<ChannelConsumer>(relaxed = true),
            dispatchManager,
            producerManager,
            mockk<CacheManager>(relaxed = true),
            mockk<FeedMessageFactory>(relaxed = true),
            recoveryManager,
            ExchangeProviderImpl()
        )
        session.open(listOf("#"), MessageInterest.ALL, mockk<OddsFeedListener>(relaxed = true), null)
        this.session = session

        // listen() subscribes on the dispatcher's own scheduler; publish probes until one is seen
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (probeSeen.count > 0 && System.nanoTime() < deadline) {
            dispatchManager.publish(oddsChange(99, null))
            probeSeen.await(50, TimeUnit.MILLISECONDS)
        }
        assertTrue("subscription never became active", probeSeen.count == 0L)

        dispatchManager.publish(oddsChange(1, 42L))
        dispatchManager.publish(oddsChange(1, null))

        verify(timeout = 5_000) { recoveryManager.onRecoveryTraffic(1L, 42L, 1234L) }
        verify(timeout = 5_000) { recoveryManager.onRecoveryTraffic(1L, null, 1234L) }
    }
}
