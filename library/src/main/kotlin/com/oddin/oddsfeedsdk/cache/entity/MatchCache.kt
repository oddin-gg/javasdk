package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.*
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability.Companion.fromApiEvent
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixturesEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEvent
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentSchedule
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.utils.Utils
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface MatchCache : Closable, CacheLoader<LocalizedMatch> {
    fun getMatch(id: URN, locales: Set<Locale>): LocalizedMatch?
}

private val logger = KotlinLogging.logger {}

class MatchCacheImpl @Inject constructor(
    private val apiClient: ApiClient,
    private val matchStatusCache: MatchStatusCache
) : MatchCache {

    companion object {
        private const val urnType = "match"
    }

    private val lock = Any()
    private val subscription: Disposable
    private val internalCache = CacheBuilder
        .newBuilder()
        .removalListener<URN, LocalizedMatch> {
            matchStatusCache.clearCacheItem(it.key)
        }
        .expireAfterWrite(12L, TimeUnit.HOURS)
        .build<URN, LocalizedMatch>()

    init {
        subscription = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .subscribe({ response ->
                val locale = response.first ?: return@subscribe
                val data = response.second ?: return@subscribe

                val matches = when (data) {
                    is RAFixturesEndpoint -> listOf(data.fixture)
                    is RAScheduleEndpoint -> data.sportEvent
                    is RATournamentSchedule -> data.sportEvents.flatMap { it.sportEvent }
                    else -> null
                }

                if (matches != null) {
                    synchronized(lock) {
                        handleMatchData(locale, matches)
                    }
                }
            }, {
                logger.error { "Failed to process message in match cache - $it" }
            })
    }

    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getMatch(id: URN, locales: Set<Locale>): LocalizedMatch? {
        return loadFromCache(id, locales)
    }

    override fun getLock(): Any {
        return lock
    }

    override fun getCache(): Cache<URN, LocalizedMatch> {
        return internalCache
    }

    override fun close() {
        subscription.dispose()
    }

    override fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        runBlocking {
            locales.forEach {
                val data = try {
                    apiClient.fetchMatchSummary(id, it)
                } catch (e: Exception) {
                    return@forEach
                }

                if (data.sportEvent.competitors.competitor.size != 2) {
                    return@forEach
                }

                try {
                    refreshOrInsertItem(id, it, data.sportEvent)
                } catch (e: Exception) {
                    logger.error { "Failed to refresh or insert match" }
                }
            }
        }
    }

    private fun handleMatchData(locale: Locale, tournaments: List<RASportEvent>) {
        tournaments.forEach {
            val id = URN.parse(it.id)
            refreshOrInsertItem(id, locale, it)
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, data: RASportEvent) {
        var item = internalCache.getIfPresent(id)
        val homeTeamId = data.competitors?.competitor?.firstOrNull()?.id
        val homeTeamQualifier = data.competitors?.competitor?.firstOrNull()?.qualifier
        val awayTeamId = data.competitors?.competitor?.lastOrNull()?.id
        val awayTeamQualifier = data.competitors?.competitor?.lastOrNull()?.qualifier

        if (item == null) {
            item = LocalizedMatch(
                id,
                if (data.refId != null) URN.parse(data.refId) else null,
                Utils.parseDate(data.scheduled),
                Utils.parseDate(data.scheduledEnd),
                URN.parse(data.tournament.sport.id),
                URN.parse(data.tournament.id),
                if (homeTeamId != null) URN.parse(homeTeamId) else null,
                if (awayTeamId != null) URN.parse(awayTeamId) else null,
                homeTeamQualifier,
                awayTeamQualifier,
                fromApiEvent(data.liveodds)
            )
        } else {
            item.scheduledTime = Utils.parseDate(data.scheduled)
            item.scheduledEndTime = Utils.parseDate(data.scheduledEnd)
            item.sportId = URN.parse(data.tournament.sport.id)
            item.tournamentId = URN.parse(data.tournament.id)
            item.homeTeamId = if (homeTeamId != null) URN.parse(homeTeamId) else null
            item.awayTeamId = if (awayTeamId != null) URN.parse(awayTeamId) else null
            item.liveOddsAvailability = fromApiEvent(data.liveodds)
            item.homeTeamQualifier = homeTeamQualifier
            item.awayTeamQualifier = awayTeamQualifier
        }

        item.name[locale] = data.name

        internalCache.put(id, item)
    }

    override fun getSupportedURNType(): String {
       return urnType
    }

}

data class LocalizedMatch(
    val id: URN,
    val refId: URN?,
    var scheduledTime: Date?,
    var scheduledEndTime: Date?,
    var sportId: URN,
    var tournamentId: URN,
    var homeTeamId: URN?,
    var awayTeamId: URN?,
    var homeTeamQualifier: String?,
    var awayTeamQualifier: String?,
    var liveOddsAvailability: LiveOddsAvailability?
) : LocalizedItem {
    val name = ConcurrentHashMap<Locale, String>()

    override val loadedLocales: Set<Locale>
        get() = name.keys
}

class MatchImpl(
    override val id: URN,
    private var localSportId: URN?,
    private val matchCache: MatchCache,
    private val entityFactory: EntityFactory,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : Match {

    override val refId: URN?
        get() = fetchMatch(locales)?.refId

    override val status: MatchStatus?
        get() = entityFactory.buildMatchStatus(id, locales.toList())

    override val tournament: Tournament?
        get() = fetchTournament()

    override val homeCompetitor: TeamCompetitor?
        get() {
            val match = fetchMatch(locales) ?: return null
            val competitor = fetchCompetitor(match.homeTeamId)

            return if (competitor != null) {
                TeamCompetitorImpl(match.homeTeamQualifier, competitor)
            } else {
                null
            }
        }


    override val awayCompetitor: TeamCompetitor?
        get() {
            val match = fetchMatch(locales) ?: return null
            val competitor = fetchCompetitor(match.awayTeamId)

            return if (competitor != null) {
                TeamCompetitorImpl(match.awayTeamQualifier, competitor)
            } else {
                null
            }
        }

    override val fixture: Fixture?
        get() = entityFactory.buildFixture(id, locales.toList())

    override val competitors: List<Competitor>
        get() = listOfNotNull(homeCompetitor, awayCompetitor)

    override fun getName(locale: Locale): String? {
        return fetchMatch(setOf(locale))?.name?.get(locale)
    }

    override val sportId: URN?
        get() = fetchSport()

    override val scheduledTime: Date?
        get() = fetchMatch(locales)?.scheduledTime

    override val scheduledEndTime: Date?
        get() = fetchMatch(locales)?.scheduledEndTime

    override val liveOddsAvailability: LiveOddsAvailability?
        get() = fetchMatch(locales)?.liveOddsAvailability

    private fun fetchMatch(locales: Set<Locale>): LocalizedMatch? {
        val item = matchCache.getMatch(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Match $id not found", null)
        } else {
            item
        }
    }

    private fun fetchCompetitor(id: URN?): Competitor? {
        return when {
            id == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot fetch competitor",
                null
            )
            id == null -> null
            else -> entityFactory.buildCompetitor(id, locales.toList())
        }
    }

    private fun fetchSport(): URN? {
        val sportId = localSportId ?: fetchMatch(locales)?.sportId

        return when {
            sportId == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot load sport",
                null
            )
            sportId == null -> null
            else -> {
                localSportId = sportId
                sportId
            }
        }
    }

    private fun fetchTournament(): Tournament? {
        val sportId = sportId ?: return null
        val tournamentId = fetchMatch(locales)?.tournamentId

        return when {
            tournamentId == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot load tournament",
                null
            )
            tournamentId == null -> null
            else -> entityFactory.buildTournament(tournamentId, sportId, locales.toList())
        }
    }

}
