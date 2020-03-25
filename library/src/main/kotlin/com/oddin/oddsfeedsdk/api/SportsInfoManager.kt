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
    /**
     * Fetch all sports with default localization {@link Locale}
     *
     * @return - all sports with default localization
     */
    val sports: List<Sport>?

    /**
     * Fetch all sports with selected localization {@link Locale}
     *
     * @param locale - {@link Locale} for data
     * @return - all sports translated with selected localization
     */
    fun getSports(locale: Locale): List<Sport>?

    /**
     * Fetch all tournaments with default localization {@link Locale}
     *
     * @return - all sports translated with default localization
     */
    val activeTournaments: List<Tournament>?

    /**
     * Fetch all tournaments with selected localization {@link Locale}
     *
     * @param locale - {@link Locale} for data
     * @return - all tournaments translated with selected localization
     */
    fun getActiveTournaments(locale: Locale): List<Tournament>?

    /**
     * Fetch all tournaments for specific sport with default localization {@link Locale}
     *
     * @param sportName - specific sport for tournament
     * @return - all tournaments for specific sport translated with default localization
     */
    fun getActiveTournaments(sportName: String): List<Tournament>?

    /**
     * Fetch all tournaments for specific sport with selected localization {@link Locale}
     *
     * @param sportName - specific sport for tournament
     * @param locale - {@link Locale} for data
     * @return - all tournaments for specific sport translated with selected localization
     */
    fun getActiveTournaments(sportName: String, locale: Locale): List<Tournament>?

    /**
     * Fetch a list of all matches scheduled on the specified date with default localization
     *
     * @param date - the date of matches
     * @return - all matches scheduled on the specified date
     */
    fun getMatchesFor(date: Date): List<Match>?

    /**
     * Fetch a list of all matches scheduled on the specified date with selected localization
     *
     * @param date - the date of matches
     * @param locale - {@link Locale} for data
     * @return - all matches scheduled on the specified date
     */
    fun getMatchesFor(date: Date, locale: Locale): List<Match>?

    /**
     * Fetch all live matches with default localization
     *
     * @return - all live matches
     */
    val liveMatches: List<Match>?

    /**
     * Fetch all live matches with selected localization
     *
     * @param locale - {@link Locale} for data
     * @return - all live matches
     */
    fun getLiveMatches(locale: Locale): List<Match>?

    /**
     * Fetch specified match with default localization
     *
     * @param id - specific match id{@link com.oddin.oddsfeedsdk.schema.utils.URN}
     * @return - match with given id
     */
    fun getMatch(id: URN): Match?

    /**
     * Fetch specified match with selected localization
     *
     * @param id - specific match id{@link com.oddin.oddsfeedsdk.schema.utils.URN}
     * @param locale - {@link Locale} for data
     * @return - match with given id
     */
    fun getMatch(id: URN, locale: Locale): Match?

    /**
     * Fetch competitor for specific id with default localization
     *
     * @param id - specific competitor id {@link com.oddin.oddsfeedsdk.schema.utils.URN} identifier
     * @return - competitor with given id
     */
    fun getCompetitor(id: URN): Competitor?

    /**
     * Fetch competitor for specific id with selected localization
     *
     * @param id - specific competitor id {@link com.oddin.oddsfeedsdk.schema.utils.URN} identifier
     * @param locale - {@link Locale} for data
     * @return - competitor with given id
     */
    fun getCompetitor(id: URN, locale: Locale): Competitor?

    /**
     * Fetch the list of all fixtures that have changed in the last 24 hours
     *
     * @return - all fixtures which were changed
     */
    val fixtureChanges: List<FixtureChange>?

    /**
     * Fetch the list of all fixtures that have changed in the last 24 hours
     *
     * @param locale  - {@link Locale} for data
     * @return - all fixtures which were changed
     */
    fun getFixtureChanges(locale: Locale): List<FixtureChange>?

    /**
     * Fetch all events with prematch odd.
     *
     * @param startIndex starting index (zero based)
     * @param limit      number of matches to return (max: 1000)
     * @return a list of sport events
     */
    fun getListOfMatches(startIndex: Int, limit: Int): List<Match>?

    /**
     * Fetch all events with prematch odd.
     *
     * @param startIndex starting index (zero based)
     * @param limit      number of matches to return (max: 1000)
     * @param locale     {@link Locale} for data
     * @return a list of sport events
     */
    fun getListOfMatches(startIndex: Int, limit: Int, locale: Locale): List<Match>?

    /**
     * Fetch all the available tournaments for given sport with default localization {@link Locale}
     *
     * @param sportId - specific sport id {@link com.oddin.oddsfeedsdk.schema.utils.URN}
     * @return - all tournaments for given sport with default localization
     */
    fun getAvailableTournaments(sportId: URN): List<Tournament>?

    /**
     * Fetch all the available tournaments for given sport with selected localization {@link Locale}
     *
     * @param sportId - specific sport id {@link com.oddin.oddsfeedsdk.schema.utils.URN}
     * @param locale  - {@link Locale} for data
     * @return - all tournaments for given sport with selected localization
     */
    fun getAvailableTournaments(sportId: URN, locale: Locale): List<Tournament>?

    /**
     * Clear match from all caches
     *
     * @param id - specific match id {@link com.oddin.oddsfeedsdk.schema.utils.URN}
     */
    fun clearMatch(id: URN)

    /**
     * Clear tournament from all caches
     *
     * @param id - specific tournament id {@link com.oddin.oddsfeedsdk.schema.utils.URN}
     */
    fun clearTournament(id: URN)

    /**
     * Clear competitor from all caches
     *
     * @param id - specific competitor id {@link com.oddin.oddsfeedsdk.schema.utils.URN}
     */
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