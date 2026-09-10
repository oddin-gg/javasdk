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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit

// An API response the observer cannot process (malformed id, failing profile fetch
// under the THROW strategy, a required element missing from the XML) used to
// throw out of onNext; RxJava then disposed the subscription and that cache
// silently stopped side-loading for the rest of the process lifetime. One bad
// response must cost at most that response.
class CacheObserverResilienceTest {

    private val publisher = PublishSubject.create<Any>().toSerialized()
    private val en = Locale.ENGLISH

    private fun publish(response: Any) =
        publisher.onNext(ApiResponse(response, URI.create("http://test"), en))

    private fun <T> awaitNonNull(supplier: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("value never appeared in the cache: the observer was disposed by the earlier bad response")
    }

    @Test(timeout = 30_000)
    fun competitorObserverSurvivesAThrowingResponseUnderThrowStrategy() {
        val config = mockk<OddsFeedConfiguration> {
            every { maxCompetitorCacheSize } returns 20_000
            every { exceptionHandlingStrategy } returns ExceptionHandlingStrategy.THROW
        }
        val valid = URN.parse("od:competitor:2")
        val api = mockk<ApiClient>(relaxed = true) {
            every { subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)
            coEvery { fetchCompetitorProfile(valid, any()) } returns RATeamExtended().apply {
                id = valid.toString(); name = "Valid"; abbreviation = "VAL"; underage = 0
            }
            // A miss must not be able to fill the cache; only the observer's side-load can.
            coEvery { fetchCompetitorProfileWithPlayers(any(), any()) } throws RuntimeException("no direct load")
        }
        val cache = CompetitorCacheImpl(api, config)

        // Under THROW, handleTeamData rethrows on a garbage id -> escapes the observer body.
        publish(RATournamentInfo().apply {
            competitors = RACompetitors().apply {
                competitor.add(RATeam().apply { id = "garbage"; name = "Broken" })
            }
        })
        publish(RATournamentInfo().apply {
            competitors = RACompetitors().apply {
                competitor.add(RATeam().apply { id = valid.toString(); name = "Valid" })
            }
        })

        val stored = awaitNonNull { cache.getCompetitor(valid, setOf(en)) }
        assertEquals("Valid", stored.name[en])
    }

    @Test(timeout = 30_000)
    fun tournamentObserverSurvivesAMalformedTournamentId() {
        val valid = URN.parse("od:tournament:2")
        val api = mockk<ApiClient>(relaxed = true) {
            every { subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)
            coEvery { fetchTournament(any(), any()) } throws RuntimeException("no direct load")
        }
        val cache = TournamentCacheImpl(api)
        val sport = RASport().apply { id = "od:sport:1"; name = "Dota 2"; abbreviation = "DOTA" }

        // URN.parse("garbage") throws inside handleTournamentsData.
        publish(RATournaments().apply {
            tournament.add(RATournament().apply { id = "garbage"; name = "Broken"; this.sport = sport })
        })
        publish(RATournaments().apply {
            tournament.add(RATournament().apply { id = valid.toString(); name = "Valid"; abbreviation = "V"; this.sport = sport })
        })

        val stored = awaitNonNull { cache.getTournament(valid, setOf(en)) }
        assertEquals("Valid", stored.name[en])
    }

    @Test(timeout = 30_000)
    fun matchObserverSurvivesAResponseMissingARequiredElement() {
        val config = mockk<OddsFeedConfiguration> { every { maxMatchCacheSize } returns 10_000 }
        val valid = URN.parse("od:match:2")
        val api = mockk<ApiClient>(relaxed = true) {
            every { subscribeForClass(ApiResponse::class.java) } returns publisher.ofType(ApiResponse::class.java)
            coEvery { fetchMatchSummary(any(), any()) } throws RuntimeException("no direct load")
        }
        val cache = MatchCacheImpl(api, config)

        // JAXB does not enforce required elements, so a truncated response has a null
        // fixture; extracting the payload from it throws.
        publish(RAFixturesEndpoint())
        publish(RAScheduleEndpoint().apply {
            sportEvent.add(RASportEvent().apply {
                id = valid.toString(); name = "Valid"
                tournament = RATournament().apply {
                    id = "od:tournament:1"; sport = RASport().apply { id = "od:sport:1" }
                }
            })
        })

        val stored = awaitNonNull { cache.getMatch(valid, setOf(en)) }
        assertEquals("Valid", stored.name[en])
    }
}
