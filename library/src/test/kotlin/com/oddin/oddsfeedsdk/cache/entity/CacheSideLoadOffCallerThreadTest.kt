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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// An API response that lists competitors or players makes the competitor and
// player caches fetch every profile. That fan-out must (1) not run on the thread
// that published the response - in production that is the thread delivering
// feed messages - and (2) not hold the cache lock while the HTTP calls are in
// flight, otherwise every reader of the cache waits for the whole fan-out.
class CacheSideLoadOffCallerThreadTest {

    private val publisher = PublishSubject.create<Any>()
    private val config = mockk<OddsFeedConfiguration> {
        every { maxCompetitorCacheSize } returns 20_000
        every { maxPlayerCacheSize } returns 50_000
        every { exceptionHandlingStrategy } returns ExceptionHandlingStrategy.CATCH
    }

    private fun team(id: String, name: String) = RATeamExtended().apply {
        this.id = id; this.name = name; abbreviation = name; underage = 0
    }

    private fun player(id: String, name: String) = RAPlayerProfileEndpoint.Player().apply {
        this.id = id; this.name = name; sportID = "od:sport:1"
    }

    private fun api(): ApiClient {
        val api = mockk<ApiClient>(relaxed = true)
        every { api.subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)
        return api
    }

    private fun assertPublishReturnsImmediately(response: Any) {
        val started = System.nanoTime()
        publisher.onNext(ApiResponse(response, URI.create("http://test"), Locale.ENGLISH))
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("publishing the response blocked for $millis ms", millis < 2_000)
    }

    @Test(timeout = 30_000)
    fun competitorProfileFanOutStaysOffThePublisherAndOutsideTheLock() {
        val fetchStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = URN.parse("od:competitor:1")
        val other = URN.parse("od:competitor:2")
        val api = api()
        coEvery { api.fetchCompetitorProfile(slow, any()) } coAnswers {
            fetchStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            team("od:competitor:1", "Slow")
        }
        coEvery { api.fetchCompetitorProfileWithPlayers(other, any()) } returns RACompetitorProfileEndpoint().apply {
            competitor = team("od:competitor:2", "Other")
        }
        val cache = CompetitorCacheImpl(api, config)

        val summary = RAMatchSummaryEndpoint().apply {
            sportEvent = RASportEvent().apply {
                id = "od:match:1"
                competitors = RASportEventCompetitors().apply {
                    competitor.add(RATeamCompetitor().apply { id = "od:competitor:1"; name = "Slow" })
                }
            }
        }

        try {
            assertPublishReturnsImmediately(summary)
            assertTrue("observer never started the profile fan-out", fetchStarted.await(5, TimeUnit.SECONDS))

            // The observer is now blocked inside its HTTP call. A reader must not wait for it.
            var result: LocalizedCompetitor? = null
            val reader = thread(isDaemon = true, name = "competitor-reader") {
                result = cache.getCompetitor(other, setOf(Locale.ENGLISH))
            }
            reader.join(5_000)
            assertFalse("reader blocked behind the observer's HTTP fan-out", reader.isAlive)
            assertNotNull(result)
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 30_000)
    fun playerProfileFanOutStaysOffThePublisherAndOutsideTheLock() {
        val fetchStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = URN.parse("od:player:1")
        val other = URN.parse("od:player:2")
        val api = api()
        coEvery { api.fetchPlayerProfile(slow, any()) } coAnswers {
            fetchStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            player("od:player:1", "Slow")
        }
        coEvery { api.fetchPlayerProfile(other, any()) } returns player("od:player:2", "Other")
        val cache = PlayerCacheImpl(api, config)

        val profile = RACompetitorProfileEndpoint().apply {
            competitor = team("od:competitor:1", "Team")
            players.add(player("od:player:1", "Slow"))
        }

        try {
            assertPublishReturnsImmediately(profile)
            assertTrue("observer never started the profile fan-out", fetchStarted.await(5, TimeUnit.SECONDS))

            var result: LocalizedPlayer? = null
            val reader = thread(isDaemon = true, name = "player-reader") {
                result = cache.getPlayer(other, setOf(Locale.ENGLISH))
            }
            reader.join(5_000)
            assertFalse("reader blocked behind the observer's HTTP fan-out", reader.isAlive)
            assertNotNull(result)
        } finally {
            release.countDown()
        }
    }
}
