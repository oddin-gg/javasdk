package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.Cache
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
import com.oddin.oddsfeedsdk.utils.Utils
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


interface TournamentCache : Closable, CacheLoader<LocalizedTournament> {
    fun getTournament(id: URN, locales: Set<Locale>): LocalizedTournament?
    fun getTournamentCompetitors(id: URN, locale: Locale): List<URN>?
}

private val logger = KotlinLogging.logger {}

class TournamentCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : TournamentCache {

    companion object {
        private const val URN_TYPE = "match"
    }

    private val lock = Any()

    private val subscription: Disposable

    private val internalCache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(12L, TimeUnit.HOURS)
        .build<URN, LocalizedTournament>()

    init {
        subscription = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .subscribe({ response ->
                val locale = response.first ?: return@subscribe
                val data = response.second ?: return@subscribe

                val tournaments = when (data) {
                    is RAFixturesEndpoint -> listOf(data.fixture.tournament)
                    is RATournaments -> data.tournament
                    is RAMatchSummaryEndpoint -> listOf(data.sportEvent.tournament)
                    is RAScheduleEndpoint -> data.sportEvent.map { it.tournament }
                    is RATournamentSchedule -> data.tournament
                    is RASportTournaments -> data.tournaments?.tournament
                    else -> null
                }

                if (tournaments != null) {
                    synchronized(lock) {
                        handleTournamentsData(locale, tournaments)
                    }
                }
            }, {
                logger.error { "Failed to process message in sport cache - $it" }
            })
    }

    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getTournament(id: URN, locales: Set<Locale>): LocalizedTournament? {
        return loadFromCache(id, locales)
    }

    override fun getTournamentCompetitors(id: URN, locale: Locale): List<URN>? {
        return synchronized(lock) {
            loadAndCacheItem(id, listOf(locale))
            return@synchronized internalCache.getIfPresent(id)?.competitorIds?.toList()
        }
    }

    override fun getLock(): Any {
        return lock
    }

    override fun getCache(): Cache<URN, LocalizedTournament> {
        return internalCache
    }

    override fun close() {
        subscription.dispose()
    }

    override fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        runBlocking {
            locales.forEach {
                val data = try {
                    apiClient.fetchTournament(id, it)
                } catch (e: Exception) {
                    return@forEach
                }

                try {
                    refreshOrInsertItem(id, it, data)
                } catch (e: Exception) {
                    logger.error { "Failed to refresh or load tournament" }
                }
            }
        }
    }

    private fun handleTournamentsData(locale: Locale, tournaments: List<RATournament>) {
        tournaments.forEach {
            val id = URN.parse(it.id)
            try {
                refreshOrInsertItem(id, locale, it)
            } catch (e: Exception) {
                logger.error { "Failed to refresh or load tournament" }
            }
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, tournament: RATournament) {
        var item = internalCache.getIfPresent(id)

        if (item == null) {
            item = LocalizedTournament(
                id,
                if (tournament.refId != null) URN.parse(tournament.refId) else null,
                Utils.parseDate(tournament.tournamentLength?.startDate),
                Utils.parseDate(tournament.tournamentLength?.endDate),
                URN.parse(tournament.sport.id),
                Utils.parseDate(tournament.scheduled),
                Utils.parseDate(tournament.scheduledEnd),
                tournament.riskTier
            )
        } else {
            item.startDate = Utils.parseDate(tournament.tournamentLength?.startDate)
            item.endDate = Utils.parseDate(tournament.tournamentLength?.endDate)
            item.sportId = URN.parse(tournament.sport.id)
            item.scheduledTime = Utils.parseDate(tournament.scheduled)
            item.scheduledEndTime = Utils.parseDate(tournament.scheduledEnd)
            item.riskTier = tournament.riskTier
        }

        item.name[locale] = tournament.name
        item.abbreviation[locale] = tournament.abbreviation

        if (tournament is RATournamentExtended) {
            val ids = tournament.competitors.competitor.map { URN.parse(it.id) }
            val competitorIds = item.competitorIds ?: mutableSetOf()
            competitorIds.addAll(ids)

            item.competitorIds = competitorIds
        }

        internalCache.put(id, item)
    }

    override fun getSupportedURNType(): String {
        return URN_TYPE
    }
}

data class LocalizedTournament(
    val id: URN,
    val refId: URN?,
    var startDate: Date?,
    var endDate: Date?,
    var sportId: URN,
    var scheduledTime: Date?,
    var scheduledEndTime: Date?,
    var riskTier: Int?
) : LocalizedItem {
    val name = ConcurrentHashMap<Locale, String>()
    val abbreviation = ConcurrentHashMap<Locale, String>()
    var competitorIds: MutableSet<URN>? = null

    override val loadedLocales: Set<Locale>
        get() = name.keys.toSet()
}

class TournamentImpl(
    override val id: URN,
    override val sportId: URN,
    private val tournamentCache: TournamentCache,
    private val entityFactory: EntityFactory,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : Tournament {

    override val refId: URN?
        get() = fetchTournament(locales)?.refId

    override fun getName(locale: Locale): String? {
        return fetchTournament(setOf(locale))?.name?.get(locale)
    }

    override fun getAbbreviation(locale: Locale): String? {
        return fetchTournament(setOf(locale))?.abbreviation?.get(locale)
    }

    override val scheduledTime: Date?
        get() = fetchTournament(locales)?.scheduledTime

    override val scheduledEndTime: Date?
        get() = fetchTournament(locales)?.scheduledEndTime

    override val liveOddsAvailability: LiveOddsAvailability
        get() = LiveOddsAvailability.NOT_AVAILABLE

    override val competitors: List<Competitor>?
        get() = fetchCompetitors(locales)

    override val startDate: Date?
        get() = fetchTournament(locales)?.startDate

    override val endDate: Date?
        get() = fetchTournament(locales)?.endDate

    override val sport: SportSummary
        get() = fetchSport(locales)

    override val riskTier: Int?
        get() = fetchTournament(locales)?.riskTier

    private fun fetchTournament(locales: Set<Locale>): LocalizedTournament? {
        val item = tournamentCache.getTournament(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
        } else {
            item
        }
    }

    private fun fetchSport(locales: Set<Locale>): Sport {
        return entityFactory.buildSport(sportId, locales.toList())
    }

    private fun fetchCompetitors(locales: Set<Locale>): List<Competitor>? {
        val competitorIds =
            fetchTournament(locales)?.competitorIds ?: tournamentCache.getTournamentCompetitors(
                id,
                locales.first()
            )

        return when {
            competitorIds == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot find competitor ids",
                null
            )
            competitorIds == null -> null
            else -> entityFactory.buildCompetitors(competitorIds.toList(), locales.toList())
        }
    }
}
