package com.oddin.oddsfeedsdk.cache

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.inject.Inject
import com.google.inject.name.Named
import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.cache.entity.*
import com.oddin.oddsfeedsdk.cache.market.MarketDescriptionCache
import com.oddin.oddsfeedsdk.mq.entities.EventMessage
import com.oddin.oddsfeedsdk.mq.entities.IdMessage
import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*
import java.util.concurrent.TimeUnit

interface Closable {
    fun close()
}

interface LocalizedItem {
    val loadedLocales: Set<Locale>
}

interface CacheManager {
    val dispatchedFixtureChanges: Cache<String, String>
    val staticMatchStatuses: LocalizedStaticDataCache
    val competitorCache: CompetitorCache
    val matchCache: MatchCache
    val tournamentCache: TournamentCache
    val fixtureCache: FixtureCache
    val marketDescriptionCache: MarketDescriptionCache
    val sportDataCache: SportDataCache
    val matchStatuses: MatchStatusCache

    fun close()

    fun onFeedMessageReceived(sessionId: UUID, feedMessage: FeedMessage)
}


class CacheManagerImpl @Inject constructor(
    @Named("MatchStatusCache")
    override val staticMatchStatuses: LocalizedStaticDataCache,
    override val competitorCache: CompetitorCache,
    override val matchCache: MatchCache,
    override val tournamentCache: TournamentCache,
    override val fixtureCache: FixtureCache,
    override val marketDescriptionCache: MarketDescriptionCache,
    override val sportDataCache: SportDataCache,
    override val matchStatuses: MatchStatusCache
) : CacheManager {
    private val _dispatchedFixtureChanges: Cache<String, String> =
        CacheBuilder.newBuilder().expireAfterWrite(1L, TimeUnit.HOURS).build()
    override val dispatchedFixtureChanges: Cache<String, String> = _dispatchedFixtureChanges



    override fun close() {
        val closable = listOf(competitorCache, matchCache, tournamentCache, sportDataCache, matchStatuses)
        closable.forEach {
            try {
                it.close()
            } catch (e: Exception) {

            }
        }
    }

    override fun onFeedMessageReceived(sessionId: UUID, feedMessage: FeedMessage) {
        val eventMessage = feedMessage.message as? IdMessage ?: return
        val id = URN.parse(eventMessage.getEventId()) ?: return

        matchCache.onFeedMessageReceived(id, feedMessage)
        tournamentCache.onFeedMessageReceived(id, feedMessage)
        matchStatuses.onFeedMessageReceived(id, feedMessage)
    }
}