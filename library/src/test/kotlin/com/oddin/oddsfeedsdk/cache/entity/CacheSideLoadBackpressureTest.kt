package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The competitor observer drains far more slowly than responses can be published
// (one blocking profile fetch per competitor). The hand-off to it must be bounded:
// a backlog is dropped, not kept in memory until the process runs out of heap.
class CacheSideLoadBackpressureTest {

    @Test(timeout = 60_000)
    fun aBlockedObserverDropsTheBacklogInsteadOfBufferingIt() {
        val publisher = PublishSubject.create<Any>().toSerialized()
        val config = mockk<OddsFeedConfiguration> {
            every { maxCompetitorCacheSize } returns 20_000
            every { exceptionHandlingStrategy } returns ExceptionHandlingStrategy.CATCH
        }
        val release = CountDownLatch(1)
        val fetches = AtomicInteger()
        val api = mockk<ApiClient>(relaxed = true) {
            every { subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)
            coEvery { fetchCompetitorProfile(any(), any()) } coAnswers {
                if (fetches.incrementAndGet() == 1) release.await(30, TimeUnit.SECONDS)
                val urn = firstArg<URN>()
                RATeamExtended().apply { id = urn.toString(); name = "Team"; abbreviation = "T"; underage = 0 }
            }
        }
        CompetitorCacheImpl(api, config)

        val published = 2_000
        val started = System.nanoTime()
        for (i in 1..published) {
            val summary = RAMatchSummaryEndpoint().apply {
                sportEvent = RASportEvent().apply {
                    id = "od:match:$i"
                    competitors = RASportEventCompetitors().apply {
                        competitor.add(RATeamCompetitor().apply { id = "od:competitor:$i"; name = "Team" })
                    }
                }
            }
            publisher.onNext(ApiResponse(summary, URI.create("http://test"), Locale.ENGLISH))
        }
        val publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("publishing $published responses took $publishMillis ms; the publisher must never block", publishMillis < 5_000)

        release.countDown()
        // Let the observer drain whatever it kept.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var last = -1
        while (System.nanoTime() < deadline && fetches.get() != last) {
            last = fetches.get()
            Thread.sleep(300)
        }

        val processed = fetches.get()
        assertTrue("observer processed nothing", processed >= 1)
        assertTrue("observer kept the whole backlog ($processed of $published); the hand-off is unbounded", processed < published / 2)
    }
}
