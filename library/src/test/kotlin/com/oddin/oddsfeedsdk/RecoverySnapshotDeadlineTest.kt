package com.oddin.oddsfeedsdk

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.api.entities.ProducerScope
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// A recovery whose snapshot_complete never arrived was asked for again only once the
// maximum recovery time, 360 minutes, had passed, and a recovery the API refused waited
// as long. The producer stayed down all that time. Now a recovery waits five minutes
// after its last message for its snapshot_complete.
class RecoverySnapshotDeadlineTest {

    private val producerId = 1L
    private val producerManager = mockk<SDKProducerManager>(relaxed = true)
    private val apiClient = mockk<ApiClient>()
    private val requests = mutableListOf<Long>()
    private var accepted = true
    private var down = true
    private var now = 1_600_000_000_000L
    private lateinit var manager: RecoveryManagerImpl

    @Before
    fun setUp() {
        val producer = mockk<Producer>(relaxed = true)
        every { producer.name } returns "pre"
        every { producer.timestampForRecovery } returns null
        every { producer.producerScopes } returns setOf(ProducerScope.PREMATCH)
        every { producerManager.activeProducers } returns mapOf(producerId to producer)
        every { producerManager.getProducer(producerId) } returns producer
        every { producerManager.isProducerEnabled(producerId) } returns true
        every { producerManager.isProducerDown(producerId) } answers { down }
        every { producerManager.setProducerDown(producerId, any()) } answers { down = secondArg() }
        coEvery { apiClient.postRecovery("pre", any(), any(), any()) } answers {
            requests.add(secondArg())
            accepted
        }
        coEvery { apiClient.postEventOddsRecovery("pre", any(), any(), any()) } returns true

        manager = RecoveryManagerImpl(
            OddsFeedConfigurationBuilder().setAccessToken("token").selectTest().build(),
            producerManager,
            mockk<TaskManager>(relaxed = true),
            mockk<GlobalEventsListener>(relaxed = true),
            mockk<FeedMessageFactory>(relaxed = true),
            apiClient
        )
        manager.clock = { now }
        manager.open(false)
    }

    private fun alive() {
        manager.onAliveReceived(
            producerId,
            MessageTimestamp(now, now, now, now),
            true,
            MessageInterest.SYSTEM_ALIVE_ONLY
        )
    }

    // An alive every ten seconds, with a message before each, for the given minutes
    private fun runFor(minutes: Int, message: () -> Unit) {
        repeat(minutes * 6) {
            now += 10_000L
            message()
            alive()
        }
    }

    @Test
    fun aLostSnapshotCompleteIsAskedForAgainAfterFiveMinutes() {
        alive()
        assertEquals(1, requests.size)

        // the live feed keeps coming, but nothing of the recovery
        runFor(5) { manager.onRecoveryTraffic(producerId, null, now) }
        assertEquals("asked again before the deadline", 1, requests.size)

        runFor(1) { manager.onRecoveryTraffic(producerId, null, now) }
        assertEquals("not asked again after the deadline", 2, requests.size)
    }

    @Test
    fun aRecoveryWhoseSnapshotKeepsComingIsNotCut() {
        alive()
        val requestId = requests.single()

        runFor(30) { manager.onRecoveryTraffic(producerId, requestId, now) }
        assertEquals(1, requests.size)

        manager.onSnapshotCompleteReceived(
            producerId,
            MessageTimestamp(now, now, now, now),
            requestId,
            MessageInterest.ALL
        )
        assertFalse("producer still down after its snapshot complete", down)
        assertEquals(1, requests.size)
    }

    @Test
    fun aSnapshotQueuedBehindOlderMessagesIsNotCut() {
        val backlogGeneratedAt = now - 60 * 60 * 1000L
        alive()

        // a session working through messages generated before the request
        runFor(10) { manager.onRecoveryTraffic(producerId, null, backlogGeneratedAt) }
        assertEquals(1, requests.size)

        runFor(6) { }
        assertEquals(2, requests.size)
    }

    @Test
    fun theMaximumRecoveryTimeStillBoundsARecoveryThatKeepsComing() {
        alive()
        val requestId = requests.single()

        runFor(360) { manager.onRecoveryTraffic(producerId, requestId, now) }
        assertEquals(1, requests.size)

        runFor(1) { manager.onRecoveryTraffic(producerId, requestId, now) }
        assertEquals(2, requests.size)
    }

    @Test
    fun aRecoveryTheApiRefusedIsAskedForAgainAfterFiveMinutes() {
        accepted = false
        alive()
        assertEquals(1, requests.size)

        runFor(5) { }
        assertEquals(1, requests.size)

        runFor(1) { }
        assertEquals(2, requests.size)
    }

    // Every request of a recovery manager used to carry the same id, so a late
    // snapshot_complete of a recovery given up completed the one asked for after it.
    private fun snapshotComplete(requestId: Long) {
        manager.onSnapshotCompleteReceived(
            producerId,
            MessageTimestamp(now, now, now, now),
            requestId,
            MessageInterest.ALL
        )
    }

    @Test
    fun eachRecoveryRequestGetsANewId() {
        alive()
        val eventRequestId = manager.initiateEventOddsMessagesRecovery(producerId, URN.parse("od:match:7"))!!
        runFor(6) { }

        assertEquals(2, requests.size)
        val ids = listOf(requests[0], eventRequestId, requests[1])
        assertEquals("ids repeat: $ids", 3, ids.toSet().size)
    }

    @Test
    fun aLateSnapshotCompleteOfAGivenUpRecoveryDoesNotCompleteTheNewOne() {
        alive()
        runFor(6) { }
        assertEquals(2, requests.size)

        snapshotComplete(requests[0])
        assertTrue("the given-up recovery completed the new one", down)

        snapshotComplete(requests[1])
        assertFalse("producer still down after its snapshot complete", down)
    }
}
