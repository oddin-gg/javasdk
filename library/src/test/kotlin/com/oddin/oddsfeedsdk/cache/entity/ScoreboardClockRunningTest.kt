package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.net.URI
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.bind.JAXBContext

// clock_running on the scoreboard says whether the in-game clock runs. Absent means the feed or
// the API did not say: it is null, never false.
class ScoreboardClockRunningTest {

    private val feedContext = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.feed.v1")
    private val restContext = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.rest.v1")
    private val matchId = URN.parse("od:match:7")

    private fun attribute(value: String?) = if (value == null) "" else """ clock_running="$value""""

    private fun feedScoreboard(clockRunning: String?): Scoreboard {
        val raw = """<odds_change event_id="od:match:7" product="1" timestamp="1785831336922">
              <sport_event_status status="1" match_status="0" scoreboard_available="true" home_score="1" away_score="0">
                <scoreboard home_goals="1" away_goals="0" time="754"${attribute(clockRunning)}/>
              </sport_event_status>
              <odds/>
            </odds_change>"""
        val message = feedContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as OFOddsChange
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
        }
        val cache = MatchStatusCacheImpl(apiClient)
        cache.onFeedMessageReceived(
            matchId,
            FeedMessage(
                message,
                ByteArray(1),
                RoutingKeyInfo("hi.live.-.odds_change.-.od:match.7", null, matchId, false),
                MessageTimestamp(0, 0, 0, 0)
            )
        )
        val scoreboard = cache.getMatchStatus(matchId)?.scoreboard
        assertNotNull(scoreboard)
        assertEquals("the rest of the scoreboard is read as before", 754, scoreboard!!.time)
        return scoreboard
    }

    private fun summaryScoreboard(clockRunning: String?): Scoreboard {
        val raw = """<?xml version="1.0"?>
            <match_summary generated_at="2026-01-01T00:00:00Z">
              <sport_event id="od:match:7" name="X" scheduled="2026-01-01T12:00:00Z"/>
              <sport_event_status status="live" match_status_code="201" scoreboard_available="true" home_score="1.0" away_score="0.0">
                <scoreboard home_goals="1" away_goals="0" time="754"${attribute(clockRunning)}/>
              </sport_event_status>
            </match_summary>"""
        val summary = restContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as RAMatchSummaryEndpoint
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } returns summary
        }
        val scoreboard = MatchStatusCacheImpl(apiClient).getMatchStatus(matchId)?.scoreboard
        assertNotNull(scoreboard)
        assertEquals("the rest of the scoreboard is read as before", 754, scoreboard!!.time)
        return scoreboard
    }

    @Test
    fun feedClockRunningTrue() = assertEquals(true, feedScoreboard("true").clockRunning)

    @Test
    fun feedClockRunningFalse() = assertEquals(false, feedScoreboard("false").clockRunning)

    @Test
    fun feedClockRunningAbsentIsUnknown() = assertNull(feedScoreboard(null).clockRunning)

    @Test
    fun summaryClockRunningTrue() = assertEquals(true, summaryScoreboard("true").clockRunning)

    @Test
    fun summaryClockRunningFalse() = assertEquals(false, summaryScoreboard("false").clockRunning)

    @Test
    fun summaryClockRunningAbsentIsUnknown() = assertNull(summaryScoreboard(null).clockRunning)

    private fun feedMessage(scoreboard: String?, available: Boolean, timestamp: Long): FeedMessage {
        val raw = """<odds_change event_id="od:match:7" product="1" timestamp="$timestamp">
              <sport_event_status status="${if (available) 1 else 3}" match_status="0" scoreboard_available="$available" home_score="1" away_score="0">
                ${scoreboard ?: ""}
              </sport_event_status>
              <odds/>
            </odds_change>"""
        val message = feedContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as OFOddsChange
        return FeedMessage(
            message,
            ByteArray(1),
            RoutingKeyInfo("hi.live.-.odds_change.-.od:match.7", null, matchId, false),
            MessageTimestamp(0, 0, 0, 0)
        )
    }

    private fun feedSequence(first: String, second: String?, secondAvailable: Boolean): Scoreboard? {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
        }
        val cache = MatchStatusCacheImpl(apiClient)
        val now = System.currentTimeMillis()
        cache.onFeedMessageReceived(matchId, feedMessage(first, true, now - 2000))
        assertEquals(true, cache.getMatchStatus(matchId)?.scoreboard?.clockRunning)
        cache.onFeedMessageReceived(matchId, feedMessage(second, secondAvailable, now - 1000))
        return cache.getMatchStatus(matchId)?.scoreboard
    }

    private fun summary(scoreboard: String?, available: Boolean, generatedAt: String): RAMatchSummaryEndpoint {
        val raw = """<?xml version="1.0"?>
            <match_summary generated_at="$generatedAt">
              <sport_event id="od:match:7" name="X" scheduled="2026-01-01T12:00:00Z"/>
              <sport_event_status status="${if (available) "live" else "closed"}" match_status_code="201" scoreboard_available="$available" home_score="1.0" away_score="0.0">
                ${scoreboard ?: ""}
              </sport_event_status>
            </match_summary>"""
        return restContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as RAMatchSummaryEndpoint
    }

    private fun summarySequence(second: String?, secondAvailable: Boolean): Scoreboard? {
        val first = summary("""<scoreboard home_goals="1" away_goals="0" time="754" clock_running="true"/>""", true, "2026-01-01T00:00:00Z")
        val next = summary(second, secondAvailable, "2026-01-01T00:05:00Z")
        val subject = PublishSubject.create<ApiResponse<*>>()
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns subject
        }
        // The observer hops to Schedulers.io(); run it inline so the assertions do not race it.
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        val cache = try {
            MatchStatusCacheImpl(apiClient)
        } finally {
            RxJavaPlugins.reset()
        }
        subject.onNext(ApiResponse(first, URI("http://localhost"), Locale.ENGLISH))
        assertEquals(true, cache.getMatchStatus(matchId)?.scoreboard?.clockRunning)
        subject.onNext(ApiResponse(next, URI("http://localhost"), Locale.ENGLISH))
        return cache.getMatchStatus(matchId)?.scoreboard
    }

    private val runningBoard = """<scoreboard home_goals="1" away_goals="0" time="754" clock_running="true"/>"""
    private val stoppedBoard = """<scoreboard home_goals="2" away_goals="0" time="900" clock_running="false"/>"""

    @Test
    fun feedStatusWithoutScoreboardKeepsTheValuesAndForgetsTheClockFlag() {
        val kept = feedSequence(runningBoard, null, false)
        assertNotNull(kept)
        assertNull(kept!!.clockRunning)
        assertEquals(1, kept.homeGoals)
        assertEquals(754, kept.time)
    }

    @Test
    fun summaryStatusWithoutScoreboardKeepsTheValuesAndForgetsTheClockFlag() {
        val kept = summarySequence(null, false)
        assertNotNull(kept)
        assertNull(kept!!.clockRunning)
        assertEquals(1, kept.homeGoals)
        assertEquals(754, kept.time)
    }

    @Test
    fun feedStatusWithAScoreboardThatSaysFalseIsFalse() {
        val next = feedSequence(runningBoard, stoppedBoard, true)
        assertEquals(false, next!!.clockRunning)
        assertEquals(2, next.homeGoals)
    }

    @Test
    fun summaryStatusWithAScoreboardThatSaysFalseIsFalse() {
        val next = summarySequence(stoppedBoard, true)
        assertEquals(false, next!!.clockRunning)
        assertEquals(2, next.homeGoals)
    }

    // The constructor and copy of the releases before clockRunning still exist and leave the flag
    // unknown (constructor) or keep it (copy).
    @Test
    fun theConstructorAndCopyOfEarlierReleasesStillExist() {
        val types = Scoreboard::class.java.constructors.single { it.parameterCount == 36 }
            .parameterTypes.copyOf(35)
        val nulls = arrayOfNulls<Any>(35)

        val created = Scoreboard::class.java.getConstructor(*types).newInstance(*nulls) as Scoreboard
        assertNull(created.clockRunning)

        val copied = Scoreboard::class.java.getMethod("copy", *types)
            .invoke(feedScoreboard("true"), *nulls) as Scoreboard
        assertEquals("the copy of earlier releases keeps the clock flag", true, copied.clockRunning)
    }
}
