package com.oddin.oddsfeedsdk.api

import com.oddin.oddsfeedsdk.DispatchManagerImpl
import com.oddin.oddsfeedsdk.config.Environment
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener
import io.mockk.every
import io.mockk.mockk
import io.reactivex.disposables.Disposable
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The raw API data callback is client code. One throwing invocation used to reach
// RxJava's onError, which disposed the subscription: no later API response ever
// reached the client again, and nothing could re-establish it short of a restart.
class ApiClientRawDataCallbackTest {

    private val config = mockk<OddsFeedConfiguration> {
        every { accessToken } returns "token"
        every { selectedEnvironment } returns Environment("mq.test", "api.test", 5672)
    }

    private fun response(body: Any) = ApiResponse(body, URI.create("http://test"), null)

    @Test(timeout = 30_000)
    fun aThrowingCallbackDoesNotStopLaterRawApiData() {
        val dispatchManager = DispatchManagerImpl()
        val client = ApiClientImpl(config, dispatchManager)
        val probe = Any()
        val throwing = Any()
        val afterwards = Any()
        val probeSeen = CountDownLatch(1)
        val afterwardsSeen = CountDownLatch(1)
        val listener = mockk<OddsFeedExtListener>(relaxed = true) {
            every { onRawApiDataReceived(any(), probe) } answers { probeSeen.countDown() }
            every { onRawApiDataReceived(any(), throwing) } throws RuntimeException("client callback failed")
            every { onRawApiDataReceived(any(), afterwards) } answers { afterwardsSeen.countDown() }
        }
        client.subscribeForData(listener)

        try {
            // listen() subscribes on the dispatcher's own scheduler; publish probes until active.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (probeSeen.count > 0 && System.nanoTime() < deadline) {
                dispatchManager.publish(response(probe))
                probeSeen.await(50, TimeUnit.MILLISECONDS)
            }
            assertTrue("subscription never became active", probeSeen.count == 0L)

            dispatchManager.publish(response(throwing))
            dispatchManager.publish(response(afterwards))

            assertTrue("later raw API data was not delivered", afterwardsSeen.await(5, TimeUnit.SECONDS))
        } finally {
            client.close()
        }

        val field = ApiClientImpl::class.java.getDeclaredField("rawDataSubscription").apply { isAccessible = true }
        assertTrue("close() must dispose the raw data subscription", (field.get(client) as Disposable).isDisposed)
    }
}
