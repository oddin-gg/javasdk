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
import com.oddin.oddsfeedsdk.utils.Utils
import io.reactivex.BackpressureStrategy
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

    companion object {
        private val MAX_CLOCK_SKEW_MS = TimeUnit.MINUTES.toMillis(1)
    }
    private val subscriptions = mutableListOf<Disposable>()
    private val internalCache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(20L, TimeUnit.MINUTES)
        .build<URN, LocalizedMatchStatus>()

    init {
        val disposable = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            // Never side-load on the thread that made the API call: it holds its own
            // cache lock, so a synchronous observer nests cache locks and can deadlock.
            // Bounded hand-off: a backlog is dropped and logged instead of buffered without
            // limit. Side-loading only warms the cache; a dropped response is fetched on
            // demand later.
            .toFlowable(BackpressureStrategy.MISSING)
            .onBackpressureDrop { logger.warn { "Dropping match status side-load response, the observer is behind" } }
            .observeOn(Schedulers.io())
            .subscribe({ response ->
                // Everything in here is guarded: an exception escaping onNext disposes the
                // subscription and the cache would stop side-loading for good.
                try {
                    val data = response.second ?: return@subscribe

                    val summary = when (data) {
                        is RAMatchSummaryEndpoint -> data
                        else -> return@subscribe
                    }
                    // A scheduled event may carry no status yet.
                    val status = summary.sportEventStatus ?: return@subscribe
                    val id = URN.parse(summary.sportEvent.id)
                    val generatedAt = Utils.parseDate(summary.generatedAt)?.time

                    synchronized(lock) {
                        applyApiSnapshot(id, status, generatedAt)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to side-load match status" }
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
        internalCache.getIfPresent(id)?.let { return it }

        val summary = runBlocking {
            try {
                apiClient.fetchMatchSummary(id, Locale.ENGLISH)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch match summary for $id" }
                null
            }
        } ?: return internalCache.getIfPresent(id)

        // Store the status from the response we just received instead of relying on
        // the asynchronous ApiResponse observer having run already.
        val status = summary.sportEventStatus ?: return internalCache.getIfPresent(id)
        val generatedAt = Utils.parseDate(summary.generatedAt)?.time
        return synchronized(lock) {
            try {
                applyApiSnapshot(id, status, generatedAt)
            } catch (e: Exception) {
                logger.error(e) { "Failed to store match status for $id" }
            }
            internalCache.getIfPresent(id)
        }
    }

    override fun onFeedMessageReceived(id: URN, feedMessage: FeedMessage) {
        val message = feedMessage.message as? OFOddsChange ?: return
        if (message.sportEventStatus == null) {
            return
        }

        synchronized(lock) {
            try {
                applyFeedSnapshot(id, message.sportEventStatus, message.getTimestamp())
            } catch (e: Exception) {
                logger.error(e) { "Failed to process message in match status cache - $message" }
            }
        }
    }

    override fun close() {
        subscriptions.forEach {
            it.dispose()
        }
    }

    // Feed messages are the live source: they always apply, and they move the entry's
    // watermark forward. A message claiming to come from the future (skewed producer
    // clock) must not be allowed to pin the entry against every later API snapshot.
    private fun applyFeedSnapshot(id: URN, data: OFSportEventStatus, messageTimestamp: Long) {
        val existing = internalCache.getIfPresent(id)
        val now = System.currentTimeMillis()
        val fence = if (messageTimestamp > now + MAX_CLOCK_SKEW_MS) {
            logger.warn { "Feed message for $id carries a future timestamp $messageTimestamp, using receive time" }
            now
        } else {
            messageTimestamp
        }
        val item = LocalizedMatchStatus(
            winnerId = existing?.winnerId,
            status = EventStatus.fromFeedEventStatus(data.status),
            periodScores = mapFeedPeriodScores(data.periodScores?.periodScore ?: listOf()),
            matchStatusId = data.matchStatus,
            homeScore = data.homeScore,
            awayScore = data.awayScore,
            isScoreboardAvailable = data.isScoreboardAvailable,
            // Update scoreboard only when ready
            scoreboard = if (data.scoreboard != null) makeFeedScoreboard(data.scoreboard) else existing?.scoreboard,
            properties = existing?.properties ?: mutableMapOf()
        )
        item.lastUpdateTimestamp = maxOf(existing?.lastUpdateTimestamp ?: 0, fence)
        internalCache.put(id, item)
    }

    // API snapshots may arrive late (the side-loading observer is asynchronous) and out
    // of order. One is applied only when it is provably newer than what the entry
    // already holds; a snapshot without a generation time cannot prove that.
    // Entries are replaced, never mutated in place, so a reader holding the previous
    // instance keeps a consistent snapshot.
    private fun applyApiSnapshot(id: URN, data: RASportEventStatus, generatedAt: Long?): Boolean {
        val existing = internalCache.getIfPresent(id)
        if (existing != null && (generatedAt == null || generatedAt <= existing.lastUpdateTimestamp)) {
            return false
        }
        val item = LocalizedMatchStatus(
            winnerId = if (data.winnerId != null) URN.parse(data.winnerId) else null,
            status = EventStatus.fromApiEventStatus(data.status),
            periodScores = mapApiPeriodScores(data.periodScores?.periodScore ?: listOf()),
            matchStatusId = data.matchStatusCode,
            homeScore = data.homeScore,
            awayScore = data.awayScore,
            isScoreboardAvailable = data.isScoreboardAvailable,
            // Update scoreboard only when ready
            scoreboard = if (data.scoreboard != null) makeApiScoreboard(data.scoreboard) else existing?.scoreboard,
            properties = existing?.properties ?: mutableMapOf()
        )
        item.lastUpdateTimestamp = generatedAt ?: 0
        internalCache.put(id, item)
        return true
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
                homeGames = it.homeGames,
                awayGames = it.awayGames,
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
                homeGames = it.homeGames,
                awayGames = it.awayGames,
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
            elapsedTime = data.elapsedTime,
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
            elapsedTime = data.elapsedTime,
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
) {
    // Generation time (feed message timestamp or API generated_at) of the data this
    // entry holds. An API snapshot is applied only when it is newer than this.
    var lastUpdateTimestamp: Long = 0
}

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