package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.*
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedStaticData
import com.oddin.oddsfeedsdk.cache.LocalizedStaticDataCache
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFPeriodScoreType
import com.oddin.oddsfeedsdk.schema.feed.v1.OFScoreboard
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPeriodScore
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScoreboard
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.TimeUnit

interface MatchStatusCache : Closable {
    fun clearCacheItem(id: URN)
    fun getMatchStatus(id: URN): LocalizedMatchStatus?
    fun onFeedMessageReceived(id: URN, feedMessage: FeedMessage)
}

private val logger = KotlinLogging.logger {}

class MatchStatusCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MatchStatusCache {
    private val lock = Any()
    private val subscriptions = mutableListOf<Disposable>()
    private val internalCache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(20L, TimeUnit.MINUTES)
        .build<URN, LocalizedMatchStatus>()

    init {
        val disposable = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .observeOn(Schedulers.io())
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

    override fun onFeedMessageReceived(id: URN, feedMessage: FeedMessage) {
        val message = feedMessage.message as? OFOddsChange ?: return
        if (message.sportEventStatus == null) {
            return
        }

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
                data.awayScore,
                data.isScoreboardAvailable,
                makeFeedScoreboard(data.scoreboard)
            )
        } else {
            item.status = EventStatus.fromFeedEventStatus(data.status)
            item.periodScores = mapFeedPeriodScores(data.periodScores?.periodScore ?: listOf())
            item.matchStatusId = data.matchStatus
            item.homeScore = data.homeScore
            item.awayScore = data.awayScore
            item.isScoreboardAvailable = data.isScoreboardAvailable
            // Update scoreboard only when ready
            if (data.scoreboard != null) {
                item.scoreboard = makeFeedScoreboard(data.scoreboard)
            }
        }

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
                data.awayScore,
                data.isScoreboardAvailable,
                makeApiScoreboard(data.scoreboard)
            )
        } else {
            item.winnerId = if (data.winnerId != null) URN.parse(data.winnerId) else null
            item.status = EventStatus.fromApiEventStatus(data.status)
            item.periodScores = mapApiPeriodScores(data.periodScores?.periodScore ?: listOf())
            item.matchStatusId = data.matchStatusCode
            item.homeScore = data.homeScore
            item.awayScore = data.awayScore
            item.isScoreboardAvailable = data.isScoreboardAvailable

            // Update scoreboard only when ready
            if (data.scoreboard != null) {
                item.scoreboard = makeApiScoreboard(data.scoreboard)
            }
        }

        internalCache.put(id, item)
    }

    private fun mapApiPeriodScores(periodScores: List<RAPeriodScore>): List<PeriodScore> {
        return periodScores.map {
            PeriodScoreImpl(
                periodType = it.type,
                homeScore = it.homeScore,
                awayScore = it.awayScore,
                periodNumber = it.number,
                matchStatusCode = it.matchStatusCode,
                homeWonRounds = it.homeWonRounds,
                awayWonRounds = it.awayWonRounds,
                homeKills = it.homeKills,
                awayKills = it.awayKills,
                homeGoals = it.homeGoals,
                awayGoals = it.awayGoals,
                homePoints = it.homePoints,
                awayPoints = it.awayPoints,
                homeRuns = it.homeRuns,
                awayRuns  = it. awayRuns,
                homeWicketsFallen = it.homeWicketsFallen,
                awayWicketsFallen = it.awayWicketsFallen,
                homeOversPlayed   = it.homeOversPlayed,
                homeBallsPlayed   = it.homeBallsPlayed,
                awayOversPlayed   = it.awayOversPlayed,
                awayBallsPlayed   = it.awayBallsPlayed,
                homeWonCoinToss   = it.homeWonCoinToss,
            )
        }.sortedBy { it.periodNumber }
    }

    private fun mapFeedPeriodScores(periodScores: List<OFPeriodScoreType>): List<PeriodScore> {
        return periodScores.map {
            PeriodScoreImpl(
                periodType = it.type,
                homeScore = it.homeScore,
                awayScore = it.awayScore,
                periodNumber = it.number,
                matchStatusCode = it.matchStatusCode,
                homeWonRounds = it.homeWonRounds,
                awayWonRounds = it.awayWonRounds,
                homeKills = it.homeKills,
                awayKills = it.awayKills,
                homeGoals = it.homeGoals,
                awayGoals = it.awayGoals,
                homePoints = it.homePoints,
                awayPoints = it.awayPoints,
                homeRuns = it.homeRuns,
                awayRuns  = it. awayRuns,
                homeWicketsFallen = it.homeWicketsFallen,
                awayWicketsFallen = it.awayWicketsFallen,
                homeOversPlayed   = it.homeOversPlayed,
                homeBallsPlayed   = it.homeBallsPlayed,
                awayOversPlayed   = it.awayOversPlayed,
                awayBallsPlayed   = it.awayBallsPlayed,
                homeWonCoinToss   = it.homeWonCoinToss,
            )
        }.sortedBy { it.periodNumber }
    }

    private fun makeFeedScoreboard(scoreboard: OFScoreboard?): Scoreboard? {
        val data = scoreboard ?: return null
        return Scoreboard(
            currentCtTeam = data.currentCTTeam,
            homeWonRounds = data.homeWonRounds,
            awayWonRounds = data.awayWonRounds,
            currentRound = data.currentRound,
            homeKills = data.homeKills,
            awayKills = data.awayKills,
            homeDestroyedTowers = data.homeDestroyedTowers,
            awayDestroyedTowers = data.awayDestroyedTowers,
            homeDestroyedTurrets = data.homeDestroyedTurrets,
            awayDestroyedTurrets = data.awayDestroyedTurrets,
            homeGold = data.homeGold,
            awayGold = data.awayGold,
            homeGoals = data.homeGoals,
            awayGoals = data.awayGoals,
            time = data.time,
            gameTime = data.gameTime,
            currentDefenderTeam = data.currentDefenderTeam,
            homePoints = data.homePoints,
            awayPoints = data.awayPoints,
            homeGames = data.homeGames,
            awayGames = data.awayGames,
            remainingGameTime = data.remainingGameTime,
            homeRuns = data.homeRuns,
            awayRuns  = data. awayRuns,
            homeWicketsFallen = data.homeWicketsFallen,
            awayWicketsFallen = data.awayWicketsFallen,
            homeOversPlayed   = data.homeOversPlayed,
            homeBallsPlayed   = data.homeBallsPlayed,
            awayOversPlayed   = data.awayOversPlayed,
            awayBallsPlayed   = data.awayBallsPlayed,
            homeWonCoinToss   = data.homeWonCoinToss,
            homeBatting =  data.homeBatting,
            awayBatting =  data.awayBatting,
            inning = data.inning
        )
    }

    private fun makeApiScoreboard(scoreboard: RAScoreboard?): Scoreboard? {
        val data = scoreboard ?: return null
        return Scoreboard(
            currentCtTeam = data.currentCTTeam,
            homeWonRounds = data.homeWonRounds,
            awayWonRounds = data.awayWonRounds,
            currentRound = data.currentRound,
            homeKills = data.homeKills,
            awayKills = data.awayKills,
            homeDestroyedTowers = data.homeDestroyedTowers,
            awayDestroyedTowers = data.awayDestroyedTowers,
            homeDestroyedTurrets = data.homeDestroyedTurrets,
            awayDestroyedTurrets = data.awayDestroyedTurrets,
            homeGold = data.homeGold,
            awayGold = data.awayGold,
            homeGoals = data.homeGoals,
            awayGoals = data.awayGoals,
            time = data.time,
            gameTime = data.gameTime,
            currentDefenderTeam = data.currentDefenderTeam,
            homePoints = data.homePoints,
            awayPoints = data.awayPoints,
            homeGames = data.homeGames,
            awayGames = data.awayGames,
            remainingGameTime = data.remainingGameTime,
            homeRuns = data.homeRuns,
            awayRuns  = data. awayRuns,
            homeWicketsFallen = data.homeWicketsFallen,
            awayWicketsFallen = data.awayWicketsFallen,
            homeOversPlayed   = data.homeOversPlayed,
            homeBallsPlayed   = data.homeBallsPlayed,
            awayOversPlayed   = data.awayOversPlayed,
            awayBallsPlayed   = data.awayBallsPlayed,
            homeWonCoinToss   = data.homeWonCoinToss,
            homeBatting =  data.homeBatting,
            awayBatting =  data.awayBatting,
            inning = data.inning
        )
    }
}

data class LocalizedMatchStatus(
    var winnerId: URN?,
    var status: EventStatus,
    var periodScores: List<PeriodScore>?,
    var matchStatusId: Int?,
    var homeScore: Double,
    var awayScore: Double,
    var isScoreboardAvailable: Boolean,
    var scoreboard: Scoreboard?,
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

    override val isScoreboardAvailable: Boolean
        get() = fetchMatchStatus()?.isScoreboardAvailable ?: false

    override val scoreboard: Scoreboard?
        get() = fetchMatchStatus()?.scoreboard

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