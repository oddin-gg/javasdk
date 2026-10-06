package com.oddin.oddsfeedsdk

import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.mq.ChannelConsumer
import com.oddin.oddsfeedsdk.mq.ExchangeProviderImpl
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSnapshotComplete
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The recovery manager gives a recovery up when nothing of it arrives for five minutes,
// so a session must tell it of every message, with the message's request id. Each kind
// of message keeps its request id in a field of its own.
@RunWith(Parameterized::class)
class OddsFeedSessionRecoveryTrafficTest(
    @Suppress("unused") private val kind: String,
    private val message: (product: Int) -> BasicMessage,
    private val expectedRequestId: Long?
) {

    companion object {
        private const val TIMESTAMP = 1234L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            case("odds_change", 42L) { OFOddsChange().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("bet_stop", 42L) { OFBetStop().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("bet_settlement", 42L) { OFBetSettlement().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("bet_cancel", 42L) { OFBetCancel().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("fixture_change", 42L) { OFFixtureChange().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("snapshot_complete", 42L) { OFSnapshotComplete().apply { setProduct(it); setTimestamp(TIMESTAMP); setRequestId(42L) } },
            case("odds_change without a request id", null) { OFOddsChange().apply { setProduct(it); setTimestamp(TIMESTAMP) } },
            case("alive", null) { OFAlive().apply { setProduct(it); setTimestamp(TIMESTAMP); setSubscribed(1) } },
        )

        private fun case(kind: String, requestId: Long?, message: (Int) -> BasicMessage): Array<Any?> =
            arrayOf(kind, message, requestId)
    }

    private val producerManager = mockk<SDKProducerManager>(relaxed = true)
    private val recoveryManager = mockk<RecoveryManagerImpl>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val dispatchManager = DispatchManagerImpl()
    private var session: OddsFeedSessionImpl? = null

    @After
    fun tearDown() {
        session?.close()
        dispatchManager.close()
    }

    private fun feedMessage(message: BasicMessage) = FeedMessage(
        message,
        ByteArray(1),
        RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.7", null, URN.parse("od:match:7"), false),
        MessageTimestamp(0, 0, 0, 0)
    )

    @Test(timeout = 30_000)
    fun theMessageReachesTheRecoveryManagerWithItsRequestId() {
        val probeSeen = CountDownLatch(1)
        every { producerManager.getProducer(any()) } returns mockk<Producer>(relaxed = true)
        every { producerManager.isProducerEnabled(any()) } returns true
        // a fixture change already dispatched would be filtered out
        every { cacheManager.dispatchedFixtureChanges.getIfPresent(any()) } returns null
        every { recoveryManager.onMessageProcessingStarted(any(), 99L, any()) } answers { probeSeen.countDown() }

        val session = OddsFeedSessionImpl(
            mockk<ChannelConsumer>(relaxed = true),
            dispatchManager,
            producerManager,
            cacheManager,
            mockk<FeedMessageFactory>(relaxed = true),
            recoveryManager,
            ExchangeProviderImpl()
        )
        session.open(listOf("#"), MessageInterest.ALL, mockk<OddsFeedListener>(relaxed = true), null)
        this.session = session

        // listen() subscribes on the dispatcher's own scheduler; publish probes until one is seen
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (probeSeen.count > 0 && System.nanoTime() < deadline) {
            dispatchManager.publish(feedMessage(OFOddsChange().apply { setProduct(99) }))
            probeSeen.await(50, TimeUnit.MILLISECONDS)
        }
        assertTrue("subscription never became active", probeSeen.count == 0L)

        dispatchManager.publish(feedMessage(message(1)))

        verify(timeout = 5_000) { recoveryManager.onRecoveryTraffic(1L, expectedRequestId, TIMESTAMP) }
    }
}
