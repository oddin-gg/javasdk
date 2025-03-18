package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus
import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface SportEvent {
    val id: URN?
    @Deprecated("This attribute is deprecated and will be removed in future.")
    val refId: URN?
    fun getName(locale: Locale): String?
    val sportId: URN?
    val scheduledTime: Date?
    val scheduledEndTime: Date?
    val liveOddsAvailability: LiveOddsAvailability?
}

interface PeriodScore {
    val periodType: String
    val homeScore: Double
    val awayScore: Double
    val periodNumber: Int
    val matchStatusCode: Int
    val homeWonRounds: Int?
    val awayWonRounds: Int?
    val homeKills: Int?
    val awayKills: Int?
    val homeGoals: Int?
    val awayGoals: Int?
    val homePoints: Int?
    val awayPoints: Int?
    val homeRuns: Int?
    val awayRuns: Int?
    val homeWicketsFallen: Int?
    val awayWicketsFallen: Int?
    val homeOversPlayed: Int?
    val homeBallsPlayed: Int?
    val awayOversPlayed: Int?
    val awayBallsPlayed: Int?
    val homeWonCoinToss: Boolean?
}

data class Scoreboard(
    val currentCtTeam: Int?,
    val homeWonRounds: Int?,
    val awayWonRounds: Int?,
    val currentRound: Int?,
    val homeKills: Int?,
    val awayKills: Int?,
    val homeDestroyedTurrets: Int?,
    val awayDestroyedTurrets: Int?,
    val homeGold: Int?,
    val awayGold: Int?,
    val homeDestroyedTowers: Int?,
    val awayDestroyedTowers: Int?,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val time: Int?,
    val gameTime: Int?,
    val currentDefenderTeam: Int?,
    val homePoints: Int?,
    val awayPoints: Int?,
    val homeGames: Int?,
    val awayGames: Int?,
    val remainingGameTime: Int?,
    val homeRuns: Int?,
    val awayRuns: Int?,
    val homeWicketsFallen: Int?,
    val awayWicketsFallen: Int?,
    val homeOversPlayed: Int?,
    val homeBallsPlayed: Int?,
    val awayOversPlayed: Int?,
    val awayBallsPlayed: Int?,
    val homeWonCoinToss: Boolean?,
    val homeBatting: Boolean?,
    val awayBatting: Boolean?,
    val inning: Int?
)

data class PeriodScoreImpl(
        override val periodType: String,
        override val homeScore: Double,
        override val awayScore: Double,
        override val periodNumber: Int,
        override val matchStatusCode: Int,
        override val homeWonRounds: Int?,
        override val awayWonRounds: Int?,
        override val homeKills: Int?,
        override val awayKills: Int?,
        override val homeGoals: Int?,
        override val awayGoals: Int?,
        override val homePoints: Int?,
        override val awayPoints: Int?,
        override val homeRuns: Int?,
        override val awayRuns: Int?,
        override val homeWicketsFallen: Int?,
        override val awayWicketsFallen: Int?,
        override val homeOversPlayed: Int?,
        override val homeBallsPlayed: Int?,
        override val awayOversPlayed: Int?,
        override val awayBallsPlayed: Int?,
        override val homeWonCoinToss: Boolean?
) : PeriodScore

interface CompetitionStatus {
    val winnerId: URN?
    val status: EventStatus?
    val properties: Map<String, Any?>?
}

interface MatchStatus : CompetitionStatus {
    val periodScores: List<PeriodScore>?
    val matchStatusId: Int?
    val matchStatus: LocalizedStaticData?
    fun getMatchStatus(locale: Locale): LocalizedStaticData?
    val homeScore: Double?
    val awayScore: Double?
    val isScoreboardAvailable: Boolean
    val scoreboard: Scoreboard?
}

interface Competition : SportEvent {
    val status: CompetitionStatus?
    val competitors: List<Competitor>?
}

interface TvChannel {
    val name: String
    val streamUrl: String
    val language: String?
}

data class TvChannelImpl(
    override val name: String,
    override val streamUrl: String,
    override val language: String?,
) : TvChannel

interface Fixture {
    val startTime: Date?
    val extraInfo: Map<String, String>?
    val tvChannels: List<TvChannel>?
}

interface Match : Competition {
    override val status: MatchStatus?
    val tournament: Tournament?
    val homeCompetitor: TeamCompetitor?
    val awayCompetitor: TeamCompetitor?
    val fixture: Fixture?
    val sportFormat: SportFormat?
    val extraInfo: Map<String, String>?
}

enum class SportFormat(val value: String) {
    CLASSIC("classic"),
    RACE("race"),
    UNKNOWN("unknown")
}

enum class HomeAway(val value: Int) {
    HOME(0), AWAY(1);
}

enum class LiveOddsAvailability(val availability: String) {
    NOT_AVAILABLE("not_available"), AVAILABLE("available");

    companion object {
        fun fromApiEvent(availability: String?): LiveOddsAvailability {
            return when (availability) {
                NOT_AVAILABLE.availability -> NOT_AVAILABLE
                else -> AVAILABLE
            }
        }
    }
}

enum class EventStatus(val apiName: String, val apiId: Int) {
    NotStarted("not_started", 0),
    Live("live", 1),
    Suspended("suspended", 2),
    Ended("ended", 3),
    Finished("closed", 4),
    Cancelled("cancelled", 5),
    Abandoned("abandoned", 6),
    Delayed("delayed", 7),
    Unknown("unknown", 8),
    Postponed("postponed", 9),
    Interrupted("interrupted", 10);

    companion object {
        fun fromApiEventStatus(status: String?): EventStatus {
            return values().firstOrNull { it.apiName == status } ?: Unknown
        }

        fun fromFeedEventStatus(status: OFEventStatus): EventStatus {
            return when (status) {
                OFEventStatus.NOT_STARTED -> NotStarted
                OFEventStatus.LIVE -> Live
                OFEventStatus.SUSPENDED -> Suspended
                OFEventStatus.ENDED -> Ended
                OFEventStatus.FINALIZED -> Finished
                else -> Unknown
            }
        }
    }

}