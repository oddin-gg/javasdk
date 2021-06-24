package com.oddin.oddsfeedsdk.cache.entity

import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.ApiResponse
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.cache.Closable
import com.oddin.oddsfeedsdk.cache.LocalizedItem
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException
import com.oddin.oddsfeedsdk.schema.rest.v1.RASport
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentInfo
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentSchedule
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

interface SportDataCache : Closable {
    fun getSports(locales: Set<Locale>): List<URN>
    fun getSport(id: URN, locales: Set<Locale>): LocalizedSport?
    fun getSportTournaments(id: URN, locale: Locale): List<URN>?
}

private val logger = KotlinLogging.logger {}

class SportDataCacheImpl @Inject constructor(
    private val apiClient: ApiClient
) : SportDataCache {
    private val lock = Any()

    private val subscription: Disposable

    private val internalCache =
        CacheBuilder
            .newBuilder()
            .build<URN, LocalizedSport>()

    private val loadedLocales = mutableSetOf<Locale>()

    init {
        subscription = apiClient
            .subscribeForClass(ApiResponse::class.java)
            .map { it.locale to it.response }
            .subscribe({ response ->
                val locale = response.first ?: return@subscribe
                val data = response.second ?: return@subscribe

                val tournamentData = when (data) {
                    is RATournamentSchedule -> data.tournament.map { it.id to it.sport }.toMap()
                    is RATournamentInfo -> mapOf(data.tournament.id to data.tournament.sport)
                    else -> null
                }

                if (tournamentData != null) {
                    synchronized(lock) {
                        handleTournamentData(locale, tournamentData)
                    }
                }
            }, {
                logger.error { "Failed to process message in sport cache - $it" }
            })
    }

    override fun getSports(locales: Set<Locale>): List<URN> {
        return synchronized(lock) {
            val missingLocales = locales.filter { !loadedLocales.contains(it) }
            if (missingLocales.isNotEmpty()) {
                loadAndCacheItem(missingLocales)
            }

            return@synchronized internalCache.asMap().values.filter { sport ->
                sport.loadedLocales.containsAll(locales)
            }.map { it.id }
        }
    }

    override fun getSport(id: URN, locales: Set<Locale>): LocalizedSport? {
        return synchronized(lock) {
            val localizedSport = internalCache.getIfPresent(id)
            val localeSet = localizedSport?.loadedLocales ?: emptySet()
            val toFetchLocales = locales.filter { !localeSet.contains(it) }

            if (toFetchLocales.isNotEmpty()) {
                loadAndCacheItem(toFetchLocales)
            }

            return@synchronized internalCache.getIfPresent(id)
        }
    }

    override fun getSportTournaments(id: URN, locale: Locale): List<URN>? {
        return synchronized(lock) {
            runBlocking {
                val tournaments = try {
                    apiClient.fetchTournaments(id, locale)
                } catch (e: Exception) {
                    return@runBlocking null
                }

                val tournamentIds = tournaments.map { URN.parse(it.id) }
                tournamentIds.forEach {
                    try {
                        refreshOrInsertItem(id, locale, tournamentId = it)
                    } catch (e: Exception) {
                        logger.error { "Failed to refresh or insert tournament to sport" }
                    }
                }

                return@runBlocking tournamentIds
            }
        }
    }

    override fun close() {
        subscription.dispose()
    }

    private fun handleTournamentData(locale: Locale, tournamentData: Map<String, RASport>) {
        tournamentData.forEach {
            val tournamentId = URN.parse(it.key)
            val sportId = URN.parse(it.value.id)

            refreshOrInsertItem(sportId, locale, sport = it.value)
            internalCache.getIfPresent(sportId)?.tournamentIds?.add(tournamentId)
        }
    }

    private fun loadAndCacheItem(locales: List<Locale>) {
        runBlocking {
            locales.forEach { locale ->
                val sports = try {
                    apiClient.fetchSports(locale)
                } catch (e: Exception) {
                    return@forEach
                }

                sports.forEach {
                    val id = URN.parse(it.id)
                    try {
                        refreshOrInsertItem(id, locale, sport = it)
                    } catch (e: Exception) {
                        logger.error { "Failed to insert or refresh sport" }
                    }
                }

                // Add locale to loaded locales
                loadedLocales.add(locale)
            }
        }
    }

    private fun refreshOrInsertItem(id: URN, locale: Locale, sport: RASport? = null, tournamentId: URN? = null) {
        var localizedSport = internalCache.getIfPresent(id)

        if (localizedSport == null) {
            localizedSport = LocalizedSport(id)
        }

        if (sport != null) {
            localizedSport.name[locale] = sport.name
            localizedSport.refId = if (sport.refId != null) URN.parse(sport.refId) else null
        }

        if (tournamentId != null) {
            val sportTournamentIds = localizedSport.tournamentIds ?: mutableSetOf()
            sportTournamentIds.add(tournamentId)
            localizedSport.tournamentIds = sportTournamentIds
        }

        internalCache.put(id, localizedSport)
    }
}

data class LocalizedSport(val id: URN, var refId: URN? = null) : LocalizedItem {
    var name = ConcurrentHashMap<Locale, String>()
    var tournamentIds: MutableSet<URN>? = null

    override val loadedLocales: Set<Locale>
        get() = name.keys
}

class SportImpl(
    override val id: URN,
    private val sportDataCache: SportDataCache,
    private val entityFactory: EntityFactory,
    private val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    private val locales: Set<Locale>
) : Sport {

    override val refId: URN?
        get() = fetchSport(locales)?.refId

    override val names: Map<Locale, String>?
        get() = fetchSport(locales)?.name

    override fun getName(locale: Locale): String? {
        return fetchSport(setOf(locale))?.name?.get(locale)
    }

    override val tournaments: List<Tournament>?
        get() = fetchTournaments()

    private fun fetchSport(locales: Set<Locale>): LocalizedSport? {
        val item = sportDataCache.getSport(id, locales)

        return if (item == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
            throw ItemNotFoundException("Competitor $id not found", null)
        } else {
            item
        }
    }

    private fun fetchTournaments(): List<Tournament>? {
        val tournamentIds = sportDataCache.getSport(id, locales)?.tournamentIds ?: sportDataCache.getSportTournaments(
            id,
            locales.first()
        )

        return when {
            tournamentIds == null && exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW -> throw ItemNotFoundException(
                "Cannot load tournament ids",
                null
            )
            tournamentIds == null -> null
            else -> entityFactory.buildTournaments(tournamentIds.toList(), id, locales.toList())
        }
    }
}