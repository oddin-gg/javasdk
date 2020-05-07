package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScoreImpl
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedStaticData
import com.oddin.oddsfeedsdk.cache.LocalizedStaticDataCache
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodScoreType
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPeriodScore
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.TimeUnit

interface MatchStatusCache : Closable {
    fun clearCacheItem(id: URN)
    fun getMatchStatus(id: URN): LocalizedMatchStatus?
    fun onFeedMessageReceived(sessionId: UUID, feedMessage: FeedMessage)
}

private val logger = KotlinLogging.logger {}

class MatchStatusCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MatchStatusCache {
    private val lock = Any()
    private val subscriptions = mutableListOf<Disposable>()
    private val internalCache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(5L, TimeUnit.MINUTES)
        .build<URN, LocalizedMatchStatus>()

    init {
        val disposable = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .subscribe({ response ->
                val data = response.second ?: return@subscribe

                val summary = when (data) {
                    is RAMatchSummaryEndpoint -> data
                    else -> null
                }

                if (summary != null) {
                    val id = URN.parse(summary.sportEvent.id)
                    synchronized(lock) {
                        refreshOrInsertApiItem(id, summary.sportEventStatus)
                    }
                }
            }, {
                logger.error { "Failed to process message in match status cache - $it" }
            })

        subscriptions.add(disposable)
    }

    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getMatchStatus(id: URN): LocalizedMatchStatus? {
        var matchStatus = internalCache.getIfPresent(id)
        if (matchStatus == null) {
            matchStatus = runBlocking {
                try {
                    apiClient.fetchMatchSummary(id, Locale.ENGLISH)
                    internalCache.getIfPresent(id)
                } catch (e: Exception) {
                    null
                }
            }
        }

        return matchStatus
    }

    override fun onFeedMessageReceived(sessionId: UUID, feedMessage: FeedMessage) {
        val message = feedMessage.message as? OFOddsChange ?: return
        if (message.sportEventStatus == null) {
            return
        }

        val id = URN.parse(message.eventId)

        synchronized(lock) {
            try {
                refreshOrInsertFeedItem(id, message.sportEventStatus)
            } catch (e: Exception) {
                logger.error { "Failed to process message in match status cache - $message" }
            }
        }
    }

    override fun close() {
        subscriptions.forEach {
            it.dispose()
        }
    }

    private fun refreshOrInsertFeedItem(id: URN, data: OFSportEventStatus) {
        var item = internalCache.getIfPresent(id)

        if (item == null) {
            item = LocalizedMatchStatus(
                null,
                EventStatus.fromFeedEventStatus(data.status),
                mapFeedPeriodScores(data.periodScores?.periodScore ?: listOf()),
                data.matchStatus,
                data.homeScore,
                data.awayScore
            )
        } else {
            item.status = EventStatus.fromFeedEventStatus(data.status)
            item.periodScores = mapFeedPeriodScores(data.periodScores?.periodScore ?: listOf())
            item.matchStatusId = data.matchStatus
            item.homeScore = data.homeScore
            item.awayScore = data.awayScore
        }

        item.properties["current_ct_team"] = data.currentCtTeam

        internalCache.put(id, item)
    }

    private fun refreshOrInsertApiItem(id: URN, data: RASportEventStatus) {
        var item = internalCache.getIfPresent(id)

        if (item == null) {
            item = LocalizedMatchStatus(
                if (data.winnerId != null) URN.parse(data.winnerId) else null,
                EventStatus.fromApiEventStatus(data.status),
                mapApiPeriodScores(data.periodScores?.periodScore ?: listOf()),
                data.matchStatusCode,
                data.homeScore,
                data.awayScore
            )
        } else {
            item.winnerId = if (data.winnerId != null) URN.parse(data.winnerId) else null
            item.status = EventStatus.fromApiEventStatus(data.status)
            item.periodScores = mapApiPeriodScores(data.periodScores?.periodScore ?: listOf())
            item.matchStatusId = data.matchStatusCode
            item.homeScore = data.homeScore
            item.awayScore = data.awayScore
        }

        internalCache.put(id, item)
    }

    private fun mapApiPeriodScores(periodScores: List<RAPeriodScore>): List<PeriodScore> {
        return periodScores.map {
            PeriodScoreImpl(
                it.homeScore,
                it.awayScore,
                it.number,
                it.matchStatusCode
            )
        }.sortedBy { it.periodNumber }
    }

    private fun mapFeedPeriodScores(periodScores: List<OFPeriodScoreType>): List<PeriodScore> {
        return periodScores.map {
            PeriodScoreImpl(
                it.homeScore,
                it.awayScore,
                it.number,
                it.matchStatusCode
            )
        }.sortedBy { it.periodNumber }
    }

}

data class LocalizedMatchStatus(
    var winnerId: URN?,
    var status: EventStatus,
    var periodScores: List<PeriodScore>?,
    var matchStatusId: Int?,
    var homeScore: Double,
    var awayScore: Double,
    var properties: MutableMap<String, Any?> = mutableMapOf()
)

class MatchStatusImpl(
    private val sportEventId: URN,
    private val matchStatusCache: MatchStatusCache,
    private val localizedStaticMatchStatusCache: LocalizedStaticDataCache,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : MatchStatus {

    override val periodScores: List<PeriodScore>?
        get() = fetchMatchStatus()?.periodScores

    override val matchStatusId: Int?
        get() = fetchMatchStatus()?.matchStatusId

    override val matchStatus: LocalizedStaticData?
        get() = fetchLocalizedMatchStatus(locales)

    override val homeScore: Double?
        get() = fetchMatchStatus()?.homeScore

    override val awayScore: Double?
        get() = fetchMatchStatus()?.awayScore

    override val winnerId: URN?
        get() = fetchMatchStatus()?.winnerId

    override val status: EventStatus?
        get() = fetchMatchStatus()?.status

    override val properties: Map<String, Any?>?
        get() = fetchMatchStatus()?.properties

    override fun getMatchStatus(locale: Locale): LocalizedStaticData? {
        return fetchLocalizedMatchStatus(setOf(locale))
    }

    private fun fetchLocalizedMatchStatus(locales: Set<Locale>): LocalizedStaticData? {
        val statusId = matchStatusId
        return if (statusId == null) {
            null
        } else {
            localizedStaticMatchStatusCache.get(statusId.toLong(), locales.toList())
        }
    }

    private fun fetchMatchStatus(): LocalizedMatchStatus? {
        val item = matchStatusCache.getMatchStatus(sportEventId)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Match status for match $sportEventId not found", null)
        } else {
            item
        }
    }
}