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
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import javax.xml.XMLConstants
import javax.xml.bind.JAXBContext
import javax.xml.bind.ValidationEvent
import javax.xml.bind.annotation.XmlAttribute
import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlElementDecl
import javax.xml.bind.annotation.XmlElementRef
import javax.xml.bind.annotation.XmlElementWrapper
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Decodes every fixture vendored from the oddsfeedschema repository, the same way the SDK
 * decodes what the feed and the REST API send: one JAXB context over each schema package.
 *
 * The 1.0 line decodes the same fixtures in its own tests, so both lines are pinned to one
 * version of the schema, which vendor/oddsfeedschema/SOURCE names. A fixture fails here when
 * it does not decode to the class the SDK expects for it, when JAXB reports an element this line
 * does not know, or when it carries an attribute no field of the decoded classes binds - JAXB
 * drops those without a word, so the test walks each fixture next to its classes to find them.
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
     * Content the schema has and this line's classes do not, per fixture directory: an element by
     * its name, an attribute as element@attribute. JAXB skips them, so the SDK decodes the rest of
     * the document and the value is simply not available on this line.
     */
    private val knownGaps = mapOf(
        // the feed's winner: this line takes the winner from the REST summary only
        "feed/odds_change" to setOf("sport_event_status@winner_id"),
        "rest/tournaments" to setOf(
            // the tournaments list comes without their competitors and icons on this line
            "competitors",
            "tournament@icon_path",
            // when the answer was generated; nothing uses it
            "tournaments@generated_at"
        )
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
                for (attribute in unboundAttributes(file, result)) {
                    if (attribute in knownGaps["$wire/$directory"].orEmpty()) {
                        gapsSeen.add("$wire/$directory/$attribute")
                    } else {
                        failures.add("$wire/$directory/${file.name}: attribute $attribute has no field on this line")
                    }
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

    /** Attributes of the fixture, as element@attribute, that no field of the decoded classes binds. */
    private fun unboundAttributes(file: File, decoded: Any): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val unbound = sortedSetOf<String>()
        walk(root, decoded.javaClass, unbound)
        return unbound
    }

    private fun walk(element: Element, type: Class<*>, unbound: MutableSet<String>) {
        val fields = generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.toList()
        val bound = fields.mapNotNull { f -> f.getAnnotation(XmlAttribute::class.java)?.let { name(it.name, f) } }.toSet()
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI) {
                continue
            }
            val name = attribute.localName ?: attribute.nodeName
            if (name !in bound) {
                unbound.add("${element.localName}@$name")
            }
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            // an element this line does not know is JAXB's to report; the walk only follows known ones
            childType(fields, type, child.localName)?.let { (wrapped, childType) ->
                if (wrapped != null) {
                    val inner = child.childNodes
                    for (j in 0 until inner.length) {
                        (inner.item(j) as? Element)?.takeIf { it.localName == wrapped }?.let { walk(it, childType, unbound) }
                    }
                } else {
                    walk(child, childType, unbound)
                }
            }
        }
    }

    /** The class a child element decodes to, and, for a wrapper element, the name of the elements it wraps. */
    private fun childType(fields: List<Field>, owner: Class<*>, name: String): Pair<String?, Class<*>>? {
        for (field in fields) {
            val wrapper = field.getAnnotation(XmlElementWrapper::class.java)
            val element = field.getAnnotation(XmlElement::class.java)
            if (wrapper != null && name(wrapper.name, field) == name) {
                return Pair(element?.let { name(it.name, field) } ?: field.name, itemType(field))
            }
            if (element != null && wrapper == null && name(element.name, field) == name) {
                return Pair(null, itemType(field))
            }
            if (field.getAnnotation(XmlElementRef::class.java)?.name == name) {
                return declaredType(owner, name)?.let { Pair(null, it) }
            }
            // with field access, a field without annotations is an element named after it
            if (field.name == name && field.annotations.isEmpty()
                && !Modifier.isStatic(field.modifiers) && !Modifier.isTransient(field.modifiers)) {
                return Pair(null, itemType(field))
            }
        }
        return null
    }

    /** The element type of a list field, or the field's own type. */
    private fun itemType(field: Field): Class<*> {
        val generic = field.genericType
        if (generic is ParameterizedType) {
            return generic.actualTypeArguments[0] as Class<*>
        }
        return field.type
    }

    /** The class an element reference resolves to, from the package's ObjectFactory. */
    private fun declaredType(owner: Class<*>, name: String): Class<*>? {
        val objectFactory = Class.forName(owner.`package`.name + ".ObjectFactory")
        return objectFactory.methods.firstOrNull { method ->
            method.getAnnotation(XmlElementDecl::class.java)?.let { it.name == name && it.scope.java in setOf(owner, XmlElementDecl.GLOBAL::class.java) } == true
        }?.let { (it.genericReturnType as ParameterizedType).actualTypeArguments[0] as Class<*> }
    }

    private fun name(declared: String, field: Field) = if (declared == "##default") field.name else declared
}
