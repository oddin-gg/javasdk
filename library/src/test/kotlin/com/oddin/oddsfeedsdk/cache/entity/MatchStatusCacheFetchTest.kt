package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.net.URI
import javax.xml.datatype.DatatypeFactory
import java.util.GregorianCalendar
import java.util.TimeZone
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

    private fun summary(homeScore: Double = 2.0): RAMatchSummaryEndpoint = RAMatchSummaryEndpoint().apply {
        sportEvent = RASportEvent().apply { id = matchId.toString() }
        sportEventStatus = RASportEventStatus().apply {
            status = "live"
            matchStatusCode = 201
            this.homeScore = homeScore
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

    private fun feedOddsChange(homeScore: Double, timestamp: Long): FeedMessage {
        val message = OFOddsChange().apply {
            setProduct(1)
            setTimestamp(timestamp)
            sportEventStatus = OFSportEventStatus().apply {
                status = OFEventStatus.LIVE
                matchStatus = 201
                this.homeScore = homeScore
                awayScore = 0.0
                isScoreboardAvailable = false
            }
        }
        return FeedMessage(
            message, ByteArray(1),
            RoutingKeyInfo("hi.live.-.odds_change.-.od:match.42", null, matchId, false),
            MessageTimestamp(timestamp, timestamp, timestamp, timestamp)
        )
    }

    private fun generatedAt(millis: Long) = DatatypeFactory.newInstance().newXMLGregorianCalendar(
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    )

    // A feed update that lands while the summary request is in flight is fresher
    // than the snapshot the request returns; the fetched snapshot must not win.
    @Test
    fun aFeedUpdateArrivingDuringTheFetchIsKept() {
        lateinit var cache: MatchStatusCacheImpl
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns Observable.never()
            coEvery { fetchMatchSummary(matchId, any()) } coAnswers {
                cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 3.0, timestamp = 2_000))
                summary() // home 2.0, older
            }
        }
        cache = MatchStatusCacheImpl(apiClient)

        val status = cache.getMatchStatus(matchId)

        assertNotNull(status)
        assertEquals(3.0, status!!.homeScore, 0.0)
    }

    // The side-loading observer runs asynchronously, so a REST snapshot can be
    // applied after a newer feed message: it must be ignored, while a snapshot
    // generated after the last feed message is applied.
    @Test
    fun observerIgnoresAnApiSnapshotOlderThanTheLastFeedUpdate() {
        val subject = PublishSubject.create<Any>().toSerialized()
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns subject.ofType(ApiResponse::class.java)
        }
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        val cache = try {
            MatchStatusCacheImpl(apiClient)
        } finally {
            RxJavaPlugins.reset()
        }

        cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 3.0, timestamp = 10_000))

        subject.onNext(ApiResponse(summary().apply { generatedAt = generatedAt(5_000) }, URI.create("http://test"), Locale.ENGLISH))
        assertEquals("older API snapshot must not replace the feed status", 3.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)

        subject.onNext(ApiResponse(summary().apply { generatedAt = generatedAt(20_000) }, URI.create("http://test"), Locale.ENGLISH))
        assertEquals("newer API snapshot must be applied", 2.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)
    }

    private fun cacheWithInlineObserver(subject: Subject<Any>): MatchStatusCacheImpl {
        val apiClient = mockk<ApiClient> {
            every { subscribeForClass(ApiResponse::class.java) } returns subject.ofType(ApiResponse::class.java)
        }
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        return try {
            MatchStatusCacheImpl(apiClient)
        } finally {
            RxJavaPlugins.reset()
        }
    }

    private fun publish(subject: Subject<Any>, response: Any) =
        subject.onNext(ApiResponse(response, URI.create("http://test"), Locale.ENGLISH))

    // Two summaries for the same match can be queued and drained in either order.
    @Test
    fun observerNeverAppliesAnApiSnapshotOlderThanTheOneAlreadyStored() {
        val subject = PublishSubject.create<Any>().toSerialized()
        val cache = cacheWithInlineObserver(subject)

        publish(subject, summary(homeScore = 2.0).apply { generatedAt = generatedAt(20_000) })
        publish(subject, summary(homeScore = 5.0).apply { generatedAt = generatedAt(10_000) })

        assertEquals(2.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)
    }

    // Without a generation time the snapshot cannot prove it is newer, so an
    // existing entry is kept.
    @Test
    fun observerSkipsAnUndatedApiSnapshotWhenTheEntryExists() {
        val subject = PublishSubject.create<Any>().toSerialized()
        val cache = cacheWithInlineObserver(subject)
        cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 3.0, timestamp = 10_000))

        publish(subject, summary(homeScore = 2.0)) // no generated_at

        assertEquals(3.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)
    }

    // A feed message with a far-future timestamp must not pin the entry against
    // every later API snapshot (the API is the only source of the winner id).
    @Test
    fun aFutureFeedTimestampDoesNotPinTheEntry() {
        val subject = PublishSubject.create<Any>().toSerialized()
        val cache = cacheWithInlineObserver(subject)
        val now = System.currentTimeMillis()
        cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 3.0, timestamp = now + 86_400_000))

        publish(subject, summary(homeScore = 2.0).apply { generatedAt = generatedAt(now + 1_000) })

        assertEquals(2.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)
    }

    // Readers keep the instance they obtained; an update must produce a new one.
    @Test
    fun updatesReplaceTheEntryInsteadOfMutatingIt() {
        val subject = PublishSubject.create<Any>().toSerialized()
        val cache = cacheWithInlineObserver(subject)
        cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 1.0, timestamp = 1_000))
        val before = cache.getMatchStatus(matchId)!!

        cache.onFeedMessageReceived(matchId, feedOddsChange(homeScore = 4.0, timestamp = 2_000))

        assertEquals(1.0, before.homeScore, 0.0)
        assertEquals(4.0, cache.getMatchStatus(matchId)!!.homeScore, 0.0)
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
