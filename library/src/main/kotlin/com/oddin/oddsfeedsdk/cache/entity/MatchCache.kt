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

public const val EXTRA_INFO_KEY_SPORT_FORMAT = "sport_format"

class MatchCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : MatchCache {

    companion object {
        private const val URN_TYPE = "match"
    }

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

                try {
                    refreshOrInsertItem(id, it, data.sportEvent)
                } catch (e: Exception) {
                    logger.error { "Failed to refresh or insert match: ${e.message}" }
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
        val competitors = data.competitors?.competitor?.map{ CompetitorID(URN.parse(it.id), it.qualifier) } ?: emptyList()

        var sportFormat = SportFormat.CLASSIC
        data.extraInfo?.info?.forEach { info ->
            if (info.key == EXTRA_INFO_KEY_SPORT_FORMAT) {
                sportFormat = when (info.value) {
                    SportFormat.CLASSIC.value -> SportFormat.CLASSIC
                    SportFormat.RACE.value -> SportFormat.RACE
                    else -> {
                        throw IllegalArgumentException("Unknown sport format '$info.value' for match '$id'")
                    }
                }
            }
        }

        if (item == null) {
            item = LocalizedMatch(
                id,
                if (data.refId != null) URN.parse(data.refId) else null,
                competitors,
                Utils.parseDate(data.scheduled),
                Utils.parseDate(data.scheduledEnd),
                URN.parse(data.tournament.sport.id),
                URN.parse(data.tournament.id),
                fromApiEvent(data.liveodds),
                sportFormat,
                data.extraInfo?.info?.associate { it.key to it.value }
            )
        } else {
            item.competitors = competitors
            item.scheduledTime = Utils.parseDate(data.scheduled)
            item.scheduledEndTime = Utils.parseDate(data.scheduledEnd)
            item.sportId = URN.parse(data.tournament.sport.id)
            item.tournamentId = URN.parse(data.tournament.id)
            item.liveOddsAvailability = fromApiEvent(data.liveodds)
            item.sportFormat = sportFormat
            item.extraInfo = data.extraInfo?.info?.associate { it.key to it.value }
        }

        item.name[locale] = data.name

        internalCache.put(id, item)
    }

    override fun getSupportedURNType(): String {
       return URN_TYPE
    }

}

data class CompetitorID (
    val id: URN,
    val qualifier: String?
)

data class LocalizedMatch(
    val id: URN,
    val refId: URN?,
    var competitors: List<CompetitorID>,
    var scheduledTime: Date?,
    var scheduledEndTime: Date?,
    var sportId: URN,
    var tournamentId: URN,
    var liveOddsAvailability: LiveOddsAvailability?,
    var sportFormat: SportFormat,
    var extraInfo: Map<String, String>?
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

    override val status: MatchStatus
        get() = entityFactory.buildMatchStatus(id, locales.toList())

    override val tournament: Tournament?
        get() = fetchTournament()

    override val competitors: List<Competitor>
        get() {
            val match = fetchMatch(locales) ?: return emptyList()

            return match.competitors.map {
                val competitor = fetchCompetitor(it.id)
                val qualifier = it.qualifier

                competitor?.let { TeamCompetitorImpl(qualifier, competitor) }
            }.filterNotNull()
        }

    override val homeCompetitor: TeamCompetitor?
        get() {
            return homeAwayCompetitor(true)
        }

    override val awayCompetitor: TeamCompetitor?
        get() {
            return homeAwayCompetitor(false)
        }

    override val sportFormat: SportFormat?
        get() {
            val match = fetchMatch(locales) ?: return null
            return match.sportFormat
        }

    override val extraInfo: Map<String, String>?
        get() {
            val match = fetchMatch(locales) ?: return null
            return match.extraInfo
        }

    override val fixture: Fixture
        get() = entityFactory.buildFixture(id, locales.toList())

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

    private fun homeAwayCompetitor(home: Boolean): TeamCompetitor? {
        val match = fetchMatch(locales) ?: return null

        when {
            match.sportFormat != SportFormat.CLASSIC -> {
                val e = "Match ${match.id} is not a classic sport format"
                logger.error { e }
                if (exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                    throw IllegalArgumentException(e)
                }
                return null
            }
            match.competitors.size != 2 -> {
                val e = "Match ${match.id} has ${match.competitors.size} competitors, but only 2 are required"
                logger.error { e }
                if (exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                    throw IllegalArgumentException(e)
                }
                return null
            }
        }

        val team = if (home) match.competitors[0] else match.competitors[1]

        val competitor = fetchCompetitor(team.id)

        return if (competitor != null) {
            TeamCompetitorImpl(team.qualifier, competitor)
        } else {
            null
        }
    }

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
