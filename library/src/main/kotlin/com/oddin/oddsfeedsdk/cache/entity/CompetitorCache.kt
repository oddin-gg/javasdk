package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor
import com.oddin.oddsfeedsdk.api.entities.sportevent.TeamCompetitor
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface CompetitorCache : Closable {
    fun clearCacheItem(id: URN)
    fun getCompetitor(id: URN, locales: Set<Locale>): LocalizedCompetitor?
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

    private fun loadAndCacheItem(id: URN, locales: List<Locale>) {
        runBlocking {
            locales.forEach {
                val data = try {
                    apiClient.fetchCompetitorProfile(id, it)
                } catch (e: Exception) {
                    return@forEach
                }

                try {
                    refreshOrInsertItem(id, it, data)
                } catch (e: Exception) {
                    logger.error { "Failed to refresh or insert competitor" }
                }
            }
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, data: RATeamExtended) {
        var item = internalCache.getIfPresent(id)

        if (item == null) {
            item = LocalizedCompetitor(
                id,
                if (data.refId != null) URN.parse(data.refId) else null,
                data.isVirtual,
                data.countryCode,
                data.underage,
                data.iconPath,
            )
        } else {
            item.virtual = data.isVirtual
            item.countryCode = data.countryCode
        }

        item.name[locale] = data.name
        if (data.abbreviation != null) {
            item.abbreviation[locale] = data.abbreviation
        }

        if (data.country != null) {
            item.country[locale] = data.country
        }

        internalCache.put(id, item)
    }

    private fun handleTeamData(locale: Locale, teamsIDs: List<String>) {
        runBlocking {
            teamsIDs.forEach { id ->
                val urn = try {
                    URN.parse(id)
                } catch (e: Exception) {
                    val msg = "Failed to parse id [$id] to urn";
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
                    val msg = "Failed to fetch competitor profile for id: [$id], locale: [$locale]";
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

    private fun fetchCompetitor(locales: Set<Locale>): LocalizedCompetitor? {
        val item = competitorCache.getCompetitor(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
        } else {
            item
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

}