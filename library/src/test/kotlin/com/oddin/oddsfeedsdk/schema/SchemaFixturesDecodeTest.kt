package com.oddin.oddsfeedsdk.schema

import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetCancel
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetSettlement
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSnapshotComplete
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitorProfileEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAError
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixtureChangesEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixturesEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescriptions
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketVoidReasons
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchStatusDescriptions
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayerProfileEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplaySetContent
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportTournaments
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportsEndpoint
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentInfo
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournaments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.bind.JAXBContext
import javax.xml.bind.ValidationEvent

/**
 * Decodes every fixture vendored from the oddsfeedschema repository, the same way the SDK
 * decodes what the feed and the REST API send: one JAXB context over each schema package.
 *
 * The 1.0 line decodes the same fixtures in its own tests, so both lines are pinned to one
 * version of the schema, which vendor/oddsfeedschema/SOURCE names. A fixture fails here when
 * it does not decode to the class the SDK expects for it, or when JAXB reports anything it
 * could not place, such as an element this line does not know.
 */
class SchemaFixturesDecodeTest {

    private val fixtures = File(
        System.getProperty("schema.fixtures") ?: error("schema.fixtures is not set; run the tests through Gradle")
    )

    /** What each fixture directory must decode to. */
    private val feed = mapOf(
        "alive" to OFAlive::class.java,
        "bet_cancel" to OFBetCancel::class.java,
        "bet_settlement" to OFBetSettlement::class.java,
        "bet_stop" to OFBetStop::class.java,
        "fixture_change" to OFFixtureChange::class.java,
        "odds_change" to OFOddsChange::class.java,
        "rollback_bet_cancel" to OFRollbackBetCancel::class.java,
        "rollback_bet_settlement" to OFRollbackBetSettlement::class.java,
        "snapshot_complete" to OFSnapshotComplete::class.java
    )

    private val rest = mapOf(
        "competitor" to RACompetitorProfileEndpoint::class.java,
        "error" to RAError::class.java,
        "fixture_changes" to RAFixtureChangesEndpoint::class.java,
        "fixtures_fixture" to RAFixturesEndpoint::class.java,
        "markets" to RAMarketDescriptions::class.java,
        "match_status" to RAMatchStatusDescriptions::class.java,
        "match_summary" to RAMatchSummaryEndpoint::class.java,
        "player" to RAPlayerProfileEndpoint::class.java,
        "producers" to RAProducers::class.java,
        "replay_content" to RAReplaySetContent::class.java,
        "schedule" to RAScheduleEndpoint::class.java,
        "sport_tournaments" to RASportTournaments::class.java,
        "sports" to RASportsEndpoint::class.java,
        "tournament_info" to RATournamentInfo::class.java,
        "tournaments" to RATournaments::class.java,
        "void_reasons" to RAMarketVoidReasons::class.java,
        "whoami" to RABookmakerDetail::class.java
    )

    /** Fixture directories this line has no decoder for, and why. */
    private val notDecoded = mapOf(
        "rest/replay_status" to "0.0.x never calls the replay status endpoint",
        "rest/tournament_schedule" to "0.0.x never calls the tournament schedule endpoint"
    )

    /**
     * Elements the schema has and this line's classes do not, per fixture directory. JAXB skips
     * them, so the SDK decodes the rest of the document and the value is simply not available.
     */
    private val knownGaps = mapOf(
        "rest/tournaments" to setOf("competitors")
    )
    private val unexpectedElement = Regex("""unexpected element \(uri:"[^"]*", local:"([^"]+)"\)""")
    private val gapsSeen = mutableSetOf<String>()

    @Test
    fun everyFeedFixtureDecodesToItsMessageClass() {
        val decoded = decodeAll("feed", "com.oddin.oddsfeedsdk.schema.feed.v1", feed)
        // the SDK hands every decoded feed message on as an UnparsedMessage
        decoded.forEach { assertTrue("$it is not an UnparsedMessage", it is UnparsedMessage) }
    }

    @Test
    fun everyRestFixtureDecodesToItsEndpointClass() {
        decodeAll("rest", "com.oddin.oddsfeedsdk.schema.rest.v1", rest)
    }

    @Test
    fun everyFixtureDirectoryIsCovered() {
        // A fixture family added upstream must be placed here on purpose, not skipped quietly.
        for (wire in listOf("feed", "rest")) {
            val known = (if (wire == "feed") feed else rest).keys + notDecoded.keys
                .filter { it.startsWith("$wire/") }.map { it.removePrefix("$wire/") }
            val present = File(fixtures, wire).listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
            assertEquals("fixture directories under $wire", known.toSortedSet(), present.toSortedSet())
        }
    }

    private fun decodeAll(wire: String, schemaPackage: String, expected: Map<String, Class<*>>): List<Any> {
        val unmarshaller = JAXBContext.newInstance(schemaPackage).createUnmarshaller()
        val events = mutableListOf<ValidationEvent>()
        unmarshaller.setEventHandler { events.add(it); true }

        val failures = mutableListOf<String>()
        val decoded = mutableListOf<Any>()
        for ((directory, type) in expected) {
            val files = File(fixtures, "$wire/$directory").listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
            if (files.isEmpty()) {
                failures.add("$wire/$directory: no fixtures")
            }
            for (file in files.sortedBy { it.name }) {
                events.clear()
                val result = try {
                    unmarshaller.unmarshal(file)
                } catch (e: Exception) {
                    failures.add("$wire/$directory/${file.name}: $e")
                    continue
                }
                if (!type.isInstance(result)) {
                    failures.add("$wire/$directory/${file.name}: decoded to ${result?.javaClass?.name}, expected ${type.name}")
                }
                for (event in events) {
                    val element = unexpectedElement.find(event.message.orEmpty())?.groupValues?.get(1)
                    if (element != null && element in knownGaps["$wire/$directory"].orEmpty()) {
                        gapsSeen.add("$wire/$directory/$element")
                        continue
                    }
                    failures.add("$wire/$directory/${file.name}: ${event.message} (line ${event.locator?.lineNumber})")
                }
                decoded.add(result)
            }
        }
        // a gap that no fixture shows any more should leave the list
        knownGaps.filterKeys { it.startsWith("$wire/") }
            .flatMap { (directory, elements) -> elements.map { "$directory/$it" } }
            .filterNot { it in gapsSeen }
            .forEach { failures.add("$it: listed as a known gap, but no fixture has it") }
        assertTrue("fixtures that did not decode cleanly:\n" + failures.joinToString("\n"), failures.isEmpty())
        return decoded
    }
}
