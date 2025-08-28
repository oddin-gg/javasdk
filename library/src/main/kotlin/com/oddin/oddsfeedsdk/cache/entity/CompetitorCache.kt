package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor
import com.oddin.oddsfeedsdk.api.entities.sportevent.Player
import com.oddin.oddsfeedsdk.api.entities.sportevent.TeamCompetitor
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

interface CompetitorCache : Closable {
    fun clearCacheItem(id: URN)
    fun getCompetitor(id: URN, locales: Set<Locale>): LocalizedCompetitor?
    fun loadAndCacheItem(id: URN, locales: List<Locale>)
}

private val logger = KotlinLogging.logger {}

class CompetitorCacheImpl @Inject constructor(
    private val apiClient: ApiClient,
    private val oddsFeedConfiguration: OddsFeedConfiguration,
) : CompetitorCache {
    private val lock = Any()
    private val subscription: Disposable

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(24L, TimeUnit.HOURS)
            .build<URN, LocalizedCompetitor>()

    init {
        subscription = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .observeOn(Schedulers.io())
            .subscribe({ response ->
                val locale = response.first ?: return@subscribe
                val data = response.second ?: return@subscribe

                val teams = when (data) {
                    is RAFixturesEndpoint -> data.fixture.competitors.competitor
                    is RAMatchSummaryEndpoint -> data.sportEvent.competitors.competitor
                    is RAScheduleEndpoint -> data.sportEvent.flatMap { it.competitors.competitor }
                    is RATournamentSchedule -> data.tournament.flatMap { it.competitors.competitor }
                    is RATournamentInfo -> data.competitors.competitor
                    else -> null
                }

                if (teams != null) {
                    synchronized(lock) {
                        handleTeamData(locale, teams.map { it.id })
                    }
                }
            }, {
                logger.error { "Failed to process message in competitor cache - $it" }
            })
    }

    override fun clearCacheItem(id: URN) {
        internalCache.invalidate(id)
    }

    override fun getCompetitor(id: URN, locales: Set<Locale>): LocalizedCompetitor? {
        return synchronized(lock) {
            val localizedTeam = internalCache.getIfPresent(id)
            val localeSet = localizedTeam?.loadedLocales ?: emptySet()
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

    override fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        runBlocking {
            locales.forEach {
                val data = try {
                    apiClient.fetchCompetitorProfileWithPlayers(id, it)
                } catch (e: Exception) {
                    return@forEach
                }

                try {
                    refreshOrInsertItem(id, it, data)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to refresh or insert competitor" }
                }
            }
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, data: RATeamable) {
        var item = internalCache.getIfPresent(id)

        data class Team(
            val refId: String?,
            val name: String,
            val abbreviation: String?,
            val country: String?,
            val countryCode: String?,
            val virtual: Boolean?,
            val underage: Int,
            var iconPath: String?,
        )

        val team = when (data) {
            is RATeamExtended -> Team(
                refId = data.refId,
                name = data.name,
                abbreviation = data.abbreviation,
                country = data.country,
                countryCode = data.countryCode,
                virtual = data.isVirtual,
                underage = data.underage,
                iconPath = data.iconPath,
            )
            is RACompetitorProfileEndpoint -> Team(
                refId = data.competitor.refId,
                name = data.competitor.name,
                abbreviation = data.competitor.abbreviation,
                country = data.competitor.country,
                countryCode = data.competitor.countryCode,
                virtual = data.competitor.isVirtual,
                underage = data.competitor.underage,
                iconPath = data.competitor.iconPath,
            )
            else -> throw IllegalArgumentException("Unknown resource type: ${data::class.simpleName}")
        }

        if (item == null) {
            item = LocalizedCompetitor(
                id,
                if (team.refId != null) URN.parse(team.refId) else null,
                team.virtual,
                team.countryCode,
                team.underage,
                team.iconPath,
            )
        } else {
            item.virtual = team.virtual
            item.countryCode = team.countryCode
        }

        item.name[locale] = team.name
        if (team.abbreviation != null) {
            item.abbreviation[locale] = team.abbreviation
        }

        if (team.country != null) {
            item.country[locale] = team.country
        }

        if (data is RACompetitorProfileEndpoint) {
            val playerURNs = ArrayList<URN>(data.players.size)
            data.players.forEach{ player ->
                val playerURN = URN.parse(player.id)
                playerURNs.add(playerURN)
            }
            item.playerIDs.clear()
            item.playerIDs.addAll(playerURNs)
        }

        internalCache.put(id, item)
    }

    private fun handleTeamData(locale: Locale, teamsIDs: List<String>) {
        runBlocking {
            teamsIDs.forEach { id ->
                val urn = try {
                    URN.parse(id)
                } catch (e: Exception) {
                    val msg = "Failed to parse id [$id] to urn"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }

                val data = try {
                    apiClient.fetchCompetitorProfile(urn, locale)
                } catch (e: Exception) {
                    val msg = "Failed to fetch competitor profile for id: [$id], locale: [$locale]"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }

                try {
                    refreshOrInsertItem(urn, locale, data)
                } catch (e: Exception) {
                    val msg = "Failed to refresh or insert competitor for id: [$id], locale: [$locale]"
                    if (oddsFeedConfiguration.exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                        throw ItemNotFoundException(msg, e)
                    } else {
                        logger.error(e) { msg }
                        return@forEach
                    }
                }
            }
        }
    }
}

data class LocalizedCompetitor(
    val id: URN,
    val refId: URN?,
    var virtual: Boolean?,
    var countryCode: String?,
    val underage: Int,
    val iconPath: String?,
) : LocalizedItem {
    val country = ConcurrentHashMap<Locale, String>()
    val name = ConcurrentHashMap<Locale, String>()
    val abbreviation = ConcurrentHashMap<Locale, String>()
    val playerIDs = CopyOnWriteArrayList<URN>()

    override val loadedLocales: Set<Locale>
        get() {
            val locales = mutableSetOf<Locale>()
            // @TODO This can be tricky if I have different values in each locale cache
            locales.addAll(country.keys)
            locales.addAll(name.keys)
            locales.addAll(abbreviation.keys)

            return locales
        }
}

class CompetitorImpl(
    override val id: URN,
    private val competitorCache: CompetitorCache,
    private val entityFactory: EntityFactory,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>,
) : Competitor {

    override val refId: URN?
        get() = fetchCompetitor(locales)?.refId

    override val names: Map<Locale, String>?
        get() = fetchCompetitor(locales)?.name

    override val countries: Map<Locale, String>?
        get() = fetchCompetitor(locales)?.country

    override val abbreviations: Map<Locale, String>?
        get() = fetchCompetitor(locales)?.abbreviation

    override val virtual: Boolean?
        get() = fetchCompetitor(locales)?.virtual

    override val countryCode: String?
        get() = fetchCompetitor(locales)?.countryCode

    override val underage: Int?
        get() = fetchCompetitor(locales)?.underage

    override val iconPath: String?
        get() = fetchCompetitor(locales)?.iconPath

    override fun getCountry(locale: Locale): String? {
        return fetchCompetitor(setOf(locale))?.country?.get(locale)
    }

    override fun getAbbreviation(locale: Locale): String? {
        return fetchCompetitor(setOf(locale))?.abbreviation?.get(locale)
    }

    override fun getName(locale: Locale): String? {
        return fetchCompetitor(setOf(locale))?.name?.get(locale)
    }

    override fun getPlayers(): List<Player?>? {
        val competitor = fetchCompetitor(locales)

        // If the competitor does not contain any players, try loading them.
        if (competitor?.playerIDs.isNullOrEmpty()) {
            competitorCache.loadAndCacheItem(id, locales.toList())
        }

        return competitor?.playerIDs?.map { playerID ->
            fetchPlayer(playerID)
        }
    }

    private fun fetchCompetitor(locales: Set<Locale>): LocalizedCompetitor? {
        val item = competitorCache.getCompetitor(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
        } else {
            item
        }
    }

    private fun fetchPlayer(id: URN?): Player? {
        return when {
            id == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot fetch player",
                null
            )
            id == null -> null
            else -> entityFactory.buildPlayer(id, locales.toList())
        }
    }
}

class TeamCompetitorImpl(override val qualifier: String?, private val competitor: Competitor) : TeamCompetitor {
    override val countries: Map<Locale, String>?
        get() = competitor.countries

    override val abbreviations: Map<Locale, String>?
        get() = competitor.abbreviations

    override val virtual: Boolean?
        get() = competitor.virtual

    override val countryCode: String?
        get() = competitor.countryCode

    override fun getCountry(locale: Locale): String? {
        return competitor.getCountry(locale)
    }

    override val underage: Int?
        get() = competitor.underage

    override val iconPath: String?
        get() = competitor.iconPath

    override fun getAbbreviation(locale: Locale): String? {
        return competitor.getAbbreviation(locale)
    }

    override val id: URN?
        get() = competitor.id

    override val refId: URN?
        get() = competitor.refId

    override val names: Map<Locale, String>?
        get() = competitor.names

    override fun getName(locale: Locale): String? {
        return competitor.getName(locale)
    }

    override fun getPlayers(): List<Player?>? {
        return competitor.getPlayers()
    }
}
