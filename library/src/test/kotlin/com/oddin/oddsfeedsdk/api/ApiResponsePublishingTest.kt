package com.oddin.oddsfeedsdk.api

import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.config.Environment
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import io.mockk.every
import io.mockk.mockk
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// API responses are published from every thread that issues a request, and each
// cache observer now sits behind an observeOn hop whose queue is single-producer.
// The publisher must therefore serialize concurrent onNext calls, otherwise
// responses can be lost or corrupt the queue. The subject is private, so the test
// reaches it by reflection; the assertion itself is behavioural.
class ApiResponsePublishingTest {

    @Test(timeout = 30_000)
    fun concurrentPublishersDeliverEveryResponseThroughAnObserveOnHop() {
        val config = mockk<OddsFeedConfiguration> {
            every { accessToken } returns "token"
            every { selectedEnvironment } returns Environment("mq.test", "api.test", 5672)
        }
        val client = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true))

        val field = ApiClientImpl::class.java.getDeclaredField("publisher").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val publisher = field.get(client) as Subject<Any>

        val threads = 8
        val perThread = 500
        val received = AtomicInteger()
        val done = CountDownLatch(threads * perThread)
        client.subscribeForClass(ApiResponse::class.java)
            .observeOn(Schedulers.io())
            .subscribe { received.incrementAndGet(); done.countDown() }

        val start = CountDownLatch(1)
        val workers = (1..threads).map {
            thread(isDaemon = true) {
                start.await()
                repeat(perThread) { publisher.onNext(ApiResponse(Any(), URI.create("http://test"), null)) }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertTrue("responses were lost between concurrent publishers and the observer", done.await(10, TimeUnit.SECONDS))
        assertEquals(threads * perThread, received.get())
    }
}
