package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
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

    // The wire numbers are not EventStatus.apiId (abandoned is 9 on the feed, 6 in the
    // enum), so check what a client reads for each number the feed sends.
    @Test
    fun everyFeedStatusNumberReachesTheClientAsItsOwnStatus() {
        val expected = mapOf(
            0 to EventStatus.NotStarted,
            1 to EventStatus.Live,
            2 to EventStatus.Suspended,
            3 to EventStatus.Ended,
            4 to EventStatus.Finished,
            5 to EventStatus.Cancelled,
            6 to EventStatus.Delayed,
            7 to EventStatus.Interrupted,
            8 to EventStatus.Postponed,
            9 to EventStatus.Abandoned
        )
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
        }
        val cache = MatchStatusCacheImpl(apiClient)

        expected.forEach { (wire, status) ->
            val raw = """<odds_change event_id="od:match:$wire" product="1" timestamp="1785831336922">
                  <sport_event_status status="$wire" match_status="0"/>
                </odds_change>"""
            val message = feedContext.createUnmarshaller()
                .unmarshal(ByteArrayInputStream(raw.toByteArray())) as OFOddsChange
            val id = URN.parse("od:match:$wire")
            cache.onFeedMessageReceived(
                id,
                FeedMessage(
                    message,
                    ByteArray(1),
                    RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.$wire", null, id, false),
                    MessageTimestamp(0, 0, 0, 0)
                )
            )

            assertEquals("feed status $wire", status, cache.getMatchStatus(id)?.status)
        }
    }

    // A status number added to the feed after this release has no constant, and JAXB
    // decodes it as null. It used to fail the message, so the match kept its previous
    // status and lost the rest of the update.
    @Test
    fun aFeedStatusNumberThisSdkDoesNotKnowIsUnknown() {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
        }
        val cache = MatchStatusCacheImpl(apiClient)
        val id = URN.parse("od:match:1")
        fun send(status: Int, homeScore: Int, timestamp: Long) {
            val raw = """<odds_change event_id="od:match:1" product="1" timestamp="$timestamp">
                  <sport_event_status status="$status" match_status="0" home_score="$homeScore" away_score="0"/>
                </odds_change>"""
            val message = feedContext.createUnmarshaller()
                .unmarshal(ByteArrayInputStream(raw.toByteArray())) as OFOddsChange
            cache.onFeedMessageReceived(
                id,
                FeedMessage(
                    message,
                    ByteArray(1),
                    RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.1", null, id, false),
                    MessageTimestamp(0, 0, 0, 0)
                )
            )
        }

        send(status = 1, homeScore = 1, timestamp = 1785831336922)
        send(status = 99, homeScore = 2, timestamp = 1785831336923)

        val status = cache.getMatchStatus(id)!!
        assertEquals(EventStatus.Unknown, status.status)
        assertEquals("the rest of the message still applies", 2.0, status.homeScore, 0.0)
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
        // The observer hops to Schedulers.io(); run it inline so the assertion below
        // does not race the side-load.
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        val cache = try {
            MatchStatusCacheImpl(apiClient)
        } finally {
            RxJavaPlugins.reset()
        }

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
