package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Two cold loads running at the same time used to deadlock: loading a sport's
// tournaments holds the sport lock and publishes the response, whose observer
// in the tournament cache needs the tournament lock; loading a tournament holds
// the tournament lock and publishes a response whose observer in the sport
// cache needs the sport lock. When the observers ran synchronously on the
// publishing thread both threads blocked forever and, because listeners run on
// the AMQP delivery thread, the feed went silent without any disconnect.
//
// The fake ApiClient reproduces ApiClientImpl.fetchData: it publishes the
// ApiResponse before returning, and a latch makes sure both loads are inside
// their fetch at the same moment.
//
// The subject is deliberately a plain PublishSubject, the shape the released
// versions had. A serialized subject makes the second publisher queue its item
// and return instead of running the observer inline, which hides the deadlock on
// its own: the test would then pass against the broken caches and prove nothing.
class CacheDeadlockRegressionTest {

    private val publisher = PublishSubject.create<Any>()
    private val bothInsideFetch = CountDownLatch(2)
    private val overlapped = AtomicBoolean(true)

    private fun sport() = RASport().apply { id = "od:sport:1"; name = "Dota 2"; abbreviation = "DOTA" }

    private fun api(): ApiClient {
        val api = mockk<ApiClient>(relaxed = true)
        every { api.subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)

        coEvery { api.fetchTournaments(any(), any()) } coAnswers {
            bothInsideFetch.countDown()
            if (!bothInsideFetch.await(5, TimeUnit.SECONDS)) overlapped.set(false)
            val response = RASportTournaments().apply {
                sport = sport()
                tournaments = RATournaments().apply {
                    tournament.add(RATournament().apply {
                        id = "od:tournament:1"; name = "TI"; abbreviation = "TI"; sport = sport()
                    })
                }
            }
            publisher.onNext(ApiResponse(response, URI.create("http://test/tournaments"), Locale.ENGLISH))
            response.tournaments.tournament
        }

        coEvery { api.fetchTournament(any(), any()) } coAnswers {
            bothInsideFetch.countDown()
            if (!bothInsideFetch.await(5, TimeUnit.SECONDS)) overlapped.set(false)
            val response = RATournamentInfo().apply {
                tournament = RATournamentExtended().apply {
                    id = "od:tournament:1"; name = "TI"; abbreviation = "TI"; sport = sport()
                }
            }
            publisher.onNext(ApiResponse(response, URI.create("http://test/info"), Locale.ENGLISH))
            response.tournament
        }
        return api
    }

    @Test(timeout = 30_000)
    fun concurrentColdSportAndTournamentLoadsDoNotDeadlock() {
        val api = api()
        val sportCache = SportDataCacheImpl(api)
        val tournamentCache = TournamentCacheImpl(api)

        val sportLoad = thread(isDaemon = true, name = "sport-load") {
            sportCache.getSportTournaments(URN.parse("od:sport:1"), Locale.ENGLISH)
        }
        val tournamentLoad = thread(isDaemon = true, name = "tournament-load") {
            tournamentCache.getTournament(URN.parse("od:tournament:1"), setOf(Locale.ENGLISH))
        }
        sportLoad.join(10_000)
        tournamentLoad.join(10_000)

        assertTrue("the two loads never overlapped, the test proved nothing", overlapped.get())
        assertNull(
            "the sport and tournament caches must never hold each other's locks",
            ManagementFactory.getThreadMXBean().findDeadlockedThreads()
        )
        assertFalse("sport load did not finish", sportLoad.isAlive)
        assertFalse("tournament load did not finish", tournamentLoad.isAlive)
        assertNotNull(tournamentCache.getTournament(URN.parse("od:tournament:1"), setOf(Locale.ENGLISH)))
    }
}
