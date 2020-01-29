package com.oddin.oddsfeedsdk.api

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.entities.sportevent.*
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.config.ExceptionHandler
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.utils.URN
import kotlinx.coroutines.runBlocking
import java.util.*

interface SportsInfoManager {
    val sports: List<Sport>?
    fun getSports(locale: Locale): List<Sport>?

    val activeTournaments: List<Tournament>?
    fun getActiveTournaments(locale: Locale): List<Tournament>?
    fun getActiveTournaments(sportName: String): List<Tournament>?
    fun getActiveTournaments(sportName: String, locale: Locale): List<Tournament>?

    fun getMatchesFor(date: Date): List<Match>?
    fun getMatchesFor(date: Date, locale: Locale): List<Match>?
    val liveMatches: List<Match>?
    fun getLiveMatches(locale: Locale): List<Match>?

    fun getMatch(id: URN): Match?
    fun getMatch(id: URN, locale: Locale): Match?

    fun getCompetitor(id: URN): Competitor?
    fun getCompetitor(id: URN, locale: Locale): Competitor?

    val fixtureChanges: List<FixtureChange>?
    fun getFixtureChanges(locale: Locale): List<FixtureChange>?

    fun getListOfMatches(startIndex: Int, limit: Int): List<Match>?
    fun getListOfMatches(startIndex: Int, limit: Int, locale: Locale): List<Match>?

    fun getAvailableTournaments(sportId: URN): List<Tournament>?
    fun getAvailableTournaments(sportId: URN, locale: Locale): List<Tournament>?

    fun clearMatch(id: URN)
    fun clearTournament(id: URN)
    fun clearCompetitor(id: URN)
}

class SportsInfoManagerImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val apiClient: ApiClient,
    private val entityFactory: EntityFactory,
    private val cacheManager: CacheManager
) : SportsInfoManager, ExceptionHandler {

    override val exceptionHandlingStrategy: ExceptionHandlingStrategy
        get() = oddsFeedConfiguration.exceptionHandlingStrategy

    override val sports: List<Sport>?
        get() = getSports(oddsFeedConfiguration.defaultLocale)

    override fun getSports(locale: Locale): List<Sport>? {
        val callable = {
            entityFactory.buildSports(listOf(locale))
        }
        return wrapError(callable, "sports")
    }

    override val activeTournaments: List<Tournament>?
        get() = getActiveTournaments(oddsFeedConfiguration.defaultLocale)

    override fun getActiveTournaments(locale: Locale): List<Tournament>? {
        val sports = getSports(locale)
        return sports?.flatMap { it.tournaments ?: emptyList() }
    }

    override fun getActiveTournaments(sportName: String): List<Tournament>? {
        return getActiveTournaments(sportName, oddsFeedConfiguration.defaultLocale)
    }

    override fun getActiveTournaments(sportName: String, locale: Locale): List<Tournament>? {
        val sport = sports?.firstOrNull { it.getName(locale).equals(sportName, true) }
        return sport?.tournaments ?: emptyList()
    }

    override fun getMatchesFor(date: Date): List<Match>? {
        return getMatchesFor(date, oddsFeedConfiguration.defaultLocale)
    }
    override fun getMatchesFor(date: Date, locale: Locale): List<Match>? {
        val callable = {
            val data = runBlocking {
                apiClient.fetchMatches(date, locale)
            }

            data.map { entityFactory.buildMatch(URN.parse(it.id), listOf(locale)) }
        }

        return wrapError(callable, "liveMatches")
    }

    override val liveMatches: List<Match>?
        get() = getLiveMatches(oddsFeedConfiguration.defaultLocale)

    override fun getLiveMatches(locale: Locale): List<Match>? {
        val callable = {
            val data = runBlocking {
                apiClient.fetchLiveMatches(locale)
            }

            data.map { entityFactory.buildMatch(URN.parse(it.id), listOf(locale)) }
        }

        return wrapError(callable, "liveMatches")
    }

    override fun getMatch(id: URN): Match? {
        return getMatch(id, oddsFeedConfiguration.defaultLocale)
    }

    override fun getMatch(id: URN, locale: Locale): Match? {
        val callable = {
            entityFactory.buildMatch(id, listOf(locale))
        }

        return wrapError(callable, "sportEvent")
    }

    override fun getCompetitor(id: URN): Competitor? {
        return getCompetitor(id, oddsFeedConfiguration.defaultLocale)
    }

    override fun getCompetitor(id: URN, locale: Locale): Competitor? {
        val callable = {
            entityFactory.buildCompetitor(id, listOf(locale))
        }

        return wrapError(callable, "competitor")
    }

    override fun clearCompetitor(id: URN) {
        cacheManager.competitorCache.clearCacheItem(id)
    }

    override val fixtureChanges: List<FixtureChange>?
        get() = getFixtureChanges(oddsFeedConfiguration.defaultLocale)

    override fun getFixtureChanges(locale: Locale): List<FixtureChange>? {
        val callable = {
            runBlocking {
                apiClient.fetchFixtureChanges(locale)
                    .map { FixtureChangeImpl(URN.parse(it.sportEventId), it.updateTime.toGregorianCalendar().time) }
            }
        }

        return wrapError(callable, "fixtureChanges")
    }

    override fun getListOfMatches(startIndex: Int, limit: Int): List<Match>? {
        return getListOfMatches(startIndex, limit, oddsFeedConfiguration.defaultLocale)
    }

    override fun getListOfMatches(startIndex: Int, limit: Int, locale: Locale): List<Match>? {
        check(startIndex >= 0 && limit <= 1000 && limit >= 1) {
            "Requires startIndex >= 0 && limit <= 1000 && limit >= 1 "
        }

        val callable = {
            val data = runBlocking {
                apiClient.fetchSchedule(startIndex, limit, locale)
            }.map { URN.parse(it.id) }

            entityFactory.buildMatches(data, listOf(locale))
        }

        return wrapError(callable, "listOfSportEvents")
    }

    override fun getAvailableTournaments(sportId: URN): List<Tournament>? {
        return getAvailableTournaments(sportId, oddsFeedConfiguration.defaultLocale)
    }

    override fun getAvailableTournaments(sportId: URN, locale: Locale): List<Tournament>? {
        val callable = {
            val data = runBlocking {
                apiClient.fetchTournaments(sportId, locale)
            }

            data.map { entityFactory.buildTournament(URN.parse(it.id), sportId, listOf(locale)) }
        }

        return wrapError(callable, "availableTournaments")
    }

    override fun clearMatch(id: URN) {
        cacheManager.matchCache.clearCacheItem(id)
        cacheManager.fixtureCache.clearCacheItem(id)
    }

    override fun clearTournament(id: URN) {
        cacheManager.tournamentCache.clearCacheItem(id)
    }
}