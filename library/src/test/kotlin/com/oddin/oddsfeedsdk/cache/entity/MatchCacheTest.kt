package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportFormat
import com.oddin.oddsfeedsdk.schema.rest.v1.RAExtraInfo
import com.oddin.oddsfeedsdk.schema.rest.v1.RAInfo
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RASport
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournament
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

// CORE-3811: unknown sport_format values must cache the match as UNKNOWN
// instead of throwing — a throw here makes the match uncacheable and every
// entity accessor fail.
class MatchCacheTest {

    private val matchId = URN.parse("od:match:2886418")

    private fun summary(sportFormat: String?): RAMatchSummaryEndpoint {
        val tournament = RATournament().apply {
            id = "od:tournament:123"
            sport = RASport().apply { id = "od:sport:7" }
        }
        val event = RASportEvent().apply {
            id = matchId.toString()
            name = "Test Match"
            setTournament(tournament)
        }
        if (sportFormat != null) {
            event.extraInfo = RAExtraInfo().apply {
                info.add(RAInfo().apply {
                    key = EXTRA_INFO_KEY_SPORT_FORMAT
                    value = sportFormat
                })
            }
        }
        return RAMatchSummaryEndpoint().apply { sportEvent = event }
    }

    private fun cacheFor(sportFormat: String?): MatchCacheImpl {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(any<Class<Any>>()) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } returns summary(sportFormat)
        }
        return MatchCacheImpl(apiClient)
    }

    private fun cachedFormat(sportFormat: String?): SportFormat {
        val cache = cacheFor(sportFormat)
        cache.loadAndCacheItem(matchId, listOf(Locale.ENGLISH))
        val match = cache.getMatch(matchId, setOf(Locale.ENGLISH))
        assertNotNull("match must be cached for sport_format=$sportFormat", match)
        return match!!.sportFormat
    }

    @Test
    fun classicSportFormatIsCached() {
        assertEquals(SportFormat.CLASSIC, cachedFormat("classic"))
    }

    @Test
    fun raceSportFormatIsCached() {
        assertEquals(SportFormat.RACE, cachedFormat("race"))
    }

    @Test
    fun unknownSportFormatIsCached() {
        assertEquals(SportFormat.UNKNOWN, cachedFormat("unknown"))
    }

    @Test
    fun unrecognizedSportFormatFallsBackToUnknownInsteadOfThrowing() {
        assertEquals(SportFormat.UNKNOWN, cachedFormat("battle-royale-v2"))
    }

    @Test
    fun missingSportFormatDefaultsToClassic() {
        assertEquals(SportFormat.CLASSIC, cachedFormat(null))
    }
}
