package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.*
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface MatchCache : Closable {
    fun clearCacheItem(id: URN)
    fun getMatch(id: URN, locales: Set<Locale>): LocalizedMatch?
}

private val logger = KotlinLogging.logger {}

class MatchCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MatchCache {
    private val lock = Any()
    private val subscription: Disposable
    private val internalCache = CacheBuilder
        .newBuilder()
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
        return synchronized(lock) {
            val localizedMatch = internalCache.getIfPresent(id)
            val localeSet = localizedMatch?.loadedLocales ?: emptySet()
            val toFetchLocales = locales.filter { !localeSet.contains(it) }

            if (toFetchLocales.isNotEmpty()) {
                loadAndCacheItem(id, toFetchLocales)
            }

            return@synchronized internalCache.getIfPresent(id)
        }
    }

    override fun close() {
        subscription.dispose()
    }

    private fun loadAndCacheItem(id: URN, locales: List<Locale>) {
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

    // @TODO SportEvent status
    private fun refreshOrInsertItem(id: URN, locale: Locale, data: RASportEvent) {
        var item = internalCache.getIfPresent(id)
        val homeTeamId = data.competitors?.competitor?.firstOrNull()?.id
        val awayTeamId =data.competitors?.competitor?.lastOrNull()?.id

        if (item == null) {
            item = LocalizedMatch(
                id,
                data.scheduled?.toGregorianCalendar()?.time,
                data.scheduledEnd?.toGregorianCalendar()?.time,
                URN.parse(data.tournament.sport.id),
                URN.parse(data.tournament.id),
                if(homeTeamId != null) URN.parse(homeTeamId) else null,
                if(awayTeamId != null) URN.parse(awayTeamId) else null
            )
        } else {
            item.scheduledTime = data.scheduled?.toGregorianCalendar()?.time
            item.scheduledEndTime = data.scheduledEnd?.toGregorianCalendar()?.time
            item.sportId = URN.parse(data.tournament.sport.id)
            item.tournamentId = URN.parse(data.tournament.id)
            item.homeTeamId = if(homeTeamId != null) URN.parse(homeTeamId) else null
            item.awayTeamId = if(awayTeamId != null) URN.parse(awayTeamId) else null
        }

        item.name[locale] = data.name

        internalCache.put(id, item)
    }

}

data class LocalizedMatch(
    val id: URN,
    var scheduledTime: Date?,
    var scheduledEndTime: Date?,
    var sportId: URN,
    var tournamentId: URN,
    var homeTeamId: URN?,
    var awayTeamId: URN?
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

    // @TODO FIX ME !!!
    override val status: MatchStatus?
        get() = null

    override val tournament: Tournament?
        get() = fetchTournament()

    override val homeCompetitor: Competitor?
        get() = fetchCompetitor(fetchMatch(locales)?.homeTeamId)

    override val awayCompetitor: Competitor?
        get() = fetchCompetitor(fetchMatch(locales)?.awayTeamId)

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

    private fun fetchMatch(locales: Set<Locale>): LocalizedMatch? {
        val item = matchCache.getMatch(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
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