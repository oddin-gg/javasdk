package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import javax.xml.bind.JAXBContext

// Set-based classic sports report the games of each set on the period row,
// next to the running sets-won tally (home_score/away_score). Before games
// were modelled on period_score, JAXB silently dropped the attribute and a
// consumer rendering per-set rows showed the tally (1:0) where the set was
// actually 6:4. The scoreboard cannot recover it either - its games reset
// with every new set.
class MatchStatusCacheTest {

    private val feedContext = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.feed.v1")
    private val restContext = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.rest.v1")

    @Test
    fun feedPeriodScoresCarryGames() {
        val raw = """<odds_change event_id="od:match:11" product="1" timestamp="1785831336922">
              <sport_event_status status="1" match_status="201" scoreboard_available="true" home_score="1" away_score="0">
                <period_scores>
                  <period_score type="set" number="1" match_status_code="200" home_score="1" away_score="0" home_games="6" away_games="4"/>
                  <period_score type="set" number="2" match_status_code="201" home_score="1" away_score="0" home_games="0" away_games="1"/>
                  <period_score type="map" number="3" match_status_code="100" home_score="1" away_score="0" home_won_rounds="13"/>
                </period_scores>
                <scoreboard home_points="40" away_points="40" home_games="0" away_games="1"/>
              </sport_event_status>
              <odds/>
            </odds_change>"""
        val message = feedContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as OFOddsChange

        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
        }
        val cache = MatchStatusCacheImpl(apiClient)

        val id = URN.parse("od:match:11")
        cache.onFeedMessageReceived(
            id,
            FeedMessage(
                message,
                ByteArray(1),
                RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.11", null, id, false),
                MessageTimestamp(0, 0, 0, 0)
            )
        )

        val status = cache.getMatchStatus(id)
        assertNotNull(status)
        val periodScores = status!!.periodScores
        assertNotNull(periodScores)
        assertEquals(3, periodScores!!.size)

        // Completed set keeps its games; the set in progress carries current games.
        assertEquals(6, periodScores[0].homeGames)
        assertEquals(4, periodScores[0].awayGames)
        assertEquals(0, periodScores[1].homeGames)
        assertEquals(1, periodScores[1].awayGames)

        // A period without games leaves them null - absence stays distinguishable from 0:0.
        assertNull(periodScores[2].homeGames)
        assertNull(periodScores[2].awayGames)

        // The sets-won tally on the same row must stay readable and independent.
        assertEquals(1.0, periodScores[0].homeScore, 0.0)
        assertEquals(0.0, periodScores[0].awayScore, 0.0)
    }

    @Test
    fun apiPeriodScoresCarryGames() {
        val raw = """<?xml version="1.0"?>
            <match_summary generated_at="2026-01-01T00:00:00Z">
              <sport_event id="od:match:9" name="X" scheduled="2026-01-01T12:00:00Z"/>
              <sport_event_status status="live" match_status_code="201" scoreboard_available="true" home_score="1.0" away_score="0.0">
                <period_scores>
                  <period_score type="set" number="1" match_status_code="200" home_score="1.0" away_score="0.0" home_games="6" away_games="4"/>
                  <period_score type="set" number="2" match_status_code="201" home_score="1.0" away_score="0.0" home_games="0" away_games="1"/>
                </period_scores>
              </sport_event_status>
            </match_summary>"""
        val summary = restContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as RAMatchSummaryEndpoint

        val subject = PublishSubject.create<ApiResponse<*>>()
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns subject
        }
        val cache = MatchStatusCacheImpl(apiClient)

        subject.onNext(ApiResponse(summary, URI("http://localhost"), Locale.ENGLISH))

        val status = cache.getMatchStatus(URN.parse("od:match:9"))
        assertNotNull(status)
        val periodScores = status!!.periodScores
        assertNotNull(periodScores)
        assertEquals(2, periodScores!!.size)

        assertEquals(6, periodScores[0].homeGames)
        assertEquals(4, periodScores[0].awayGames)
        assertEquals(0, periodScores[1].homeGames)
        assertEquals(1, periodScores[1].awayGames)
    }
}
