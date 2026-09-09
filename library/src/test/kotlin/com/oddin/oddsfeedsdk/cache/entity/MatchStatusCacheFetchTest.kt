package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

// The match status fetched on a cache miss must be visible to the caller
// immediately. Earlier versions stored it only through the asynchronous
// ApiResponse observer and read the cache straight after the fetch, so the
// first read raced the observer and often returned nothing. Making the
// observers synchronous "fixed" that race but introduced a cache deadlock, so
// the status is now stored directly from the fetched summary and the observer
// is allowed to be asynchronous (here: it never delivers at all).
class MatchStatusCacheFetchTest {

    private val matchId = URN.parse("od:match:42")

    private fun summary(): RAMatchSummaryEndpoint = RAMatchSummaryEndpoint().apply {
        sportEvent = RASportEvent().apply { id = matchId.toString() }
        sportEventStatus = RASportEventStatus().apply {
            status = "live"
            matchStatusCode = 201
            homeScore = 2.0
            awayScore = 1.0
            isScoreboardAvailable = false
        }
    }

    @Test
    fun statusIsAvailableRightAfterTheFetchWithoutTheObserver() {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } returns summary()
        }
        val cache = MatchStatusCacheImpl(apiClient)

        val status = cache.getMatchStatus(matchId)

        assertNotNull("status must be stored from the fetched summary", status)
        assertEquals(EventStatus.Live, status!!.status)
        assertEquals(201, status.matchStatusId)
        assertEquals(2.0, status.homeScore, 0.0)
        assertEquals(1.0, status.awayScore, 0.0)
    }

    @Test
    fun summaryWithoutStatusYieldsNullWithoutThrowing() {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } returns RAMatchSummaryEndpoint().apply {
                sportEvent = RASportEvent().apply { id = matchId.toString() }
            }
        }

        assertNull(MatchStatusCacheImpl(apiClient).getMatchStatus(matchId))
    }

    @Test
    fun malformedWinnerIdIsContainedAndYieldsNull() {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } returns summary().apply {
                sportEventStatus.winnerId = "not-a-urn"
            }
        }

        assertNull(MatchStatusCacheImpl(apiClient).getMatchStatus(matchId))
    }

    @Test
    fun failedFetchYieldsNoStatusAndDoesNotThrow() {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } throws RuntimeException("api down")
        }
        val cache = MatchStatusCacheImpl(apiClient)

        assertNull(cache.getMatchStatus(matchId))
    }
}
