package com.oddin.oddsfeedsdk.api.factories

import com.google.inject.Inject
import com.google.inject.name.Named
import com.oddin.oddsfeedsdk.api.entities.sportevent.*
import com.oddin.oddsfeedsdk.cache.LocalizedStaticDataCache
import com.oddin.oddsfeedsdk.cache.entity.*
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface EntityFactory {
    fun buildSports(locales: List<Locale>): List<Sport>
    fun buildSport(id: URN, locales: List<Locale>): Sport

    fun buildMatches(ids: List<URN>, locales: List<Locale>): List<Match>
    fun buildMatch(id: URN, locales: List<Locale>, sportId: URN? = null): Match

    fun buildTournaments(ids: List<URN>, sportId: URN, locales: List<Locale>): List<Tournament>
    fun buildTournament(id: URN, sportId: URN, locales: List<Locale>): Tournament

    fun buildCompetitor(id: URN, locales: List<Locale>): Competitor
    fun buildCompetitors(ids: List<URN>, locales: List<Locale>): List<Competitor>

    fun buildPlayer(id: URN, locales: List<Locale>): Player
    fun buildPlayers(ids: List<URN>, locales: List<Locale>): List<Player>

    fun buildFixture(id: URN, locales: List<Locale>): Fixture

    fun buildMatchStatus(id: URN, locales: List<Locale>): MatchStatus
}

class EntityFactoryImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val competitorCache: CompetitorCache,
    private val playerCache: PlayerCache,
    private val sportDataCache: SportDataCache,
    private val tournamentCache: TournamentCache,
    private val matchCache: MatchCache,
    private val fixtureCache: FixtureCache,
    private val matchStatusCache: MatchStatusCache,
    @Named("MatchStatusCache")
    private val staticMatchStatusCache: LocalizedStaticDataCache
) : EntityFactory {
    override fun buildSports(locales: List<Locale>): List<Sport> {
        val localizedSports = sportDataCache.getSports(locales.toSet())

        return localizedSports.map {
            SportImpl(
                it,
                sportDataCache,
                this,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet()
            )
        }
    }

    override fun buildSport(id: URN, locales: List<Locale>): Sport {
        return SportImpl(
            id,
            sportDataCache,
            this,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }

    override fun buildMatches(ids: List<URN>, locales: List<Locale>): List<Match> {
        return ids.map {
            MatchImpl(
                it,
                null,
                matchCache,
                this,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet()
            )
        }
    }

    override fun buildMatch(id: URN, locales: List<Locale>, sportId: URN?): Match {
        return MatchImpl(
            id,
            sportId,
            matchCache,
            this,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }

    override fun buildTournaments(ids: List<URN>, sportId: URN, locales: List<Locale>): List<Tournament> {
        return ids.map {
            TournamentImpl(
                it,
                sportId,
                tournamentCache,
                this,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet()
            )
        }
    }

    override fun buildTournament(id: URN, sportId: URN, locales: List<Locale>): Tournament {
        return TournamentImpl(
            id,
            sportId,
            tournamentCache,
            this,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }

    override fun buildCompetitor(id: URN, locales: List<Locale>): Competitor {
        return CompetitorImpl(
            id,
            competitorCache,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet(),
        )
    }

    override fun buildCompetitors(ids: List<URN>, locales: List<Locale>): List<Competitor> {
        return ids.map {
            CompetitorImpl(
                it,
                competitorCache,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet(),
            )
        }
    }

    override fun buildPlayer(id: URN, locales: List<Locale>): Player {
        return PlayerImpl(
                id,
                playerCache,
                oddsFeedConfiguration.exceptionHandlingStrategy,
                locales.toSet(),
        )
    }

    override fun buildPlayers(ids: List<URN>, locales: List<Locale>): List<Player> {
        return ids.map {
            PlayerImpl(
                    it,
                    playerCache,
                    oddsFeedConfiguration.exceptionHandlingStrategy,
                    locales.toSet(),
            )
        }
    }

    override fun buildFixture(id: URN, locales: List<Locale>): Fixture {
        return FixtureImpl(
            id,
            fixtureCache,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }

    override fun buildMatchStatus(id: URN, locales: List<Locale>): MatchStatus {
        return MatchStatusImpl(
            id,
            matchStatusCache,
            staticMatchStatusCache,
            oddsFeedConfiguration.exceptionHandlingStrategy,
            locales.toSet()
        )
    }


}
