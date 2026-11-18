package com.oddin.oddsfeedsdk.mq.entities

import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.SDKProducerManager
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.api.factories.MarketFactory
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.mq.FeedMessageFactoryImpl
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.StringReader
import java.util.Locale
import javax.xml.bind.JAXBContext

// The feed never sends the certainty of a bet settlement; the 1.0 line answers UNKNOWN for
// every message and this line does the same. The Java-visible members stay, deprecated.
@Suppress("DEPRECATION")
class BetSettlementCertaintyTest {

    private val entityFactory = mockk<EntityFactory> {
        every { buildMatch(any(), any(), any()) } returns mockk<Match>()
    }
    private val producerManager = mockk<SDKProducerManager> {
        every { getProducer(any<Long>()) } returns mockk()
    }
    private val config = mockk<OddsFeedConfiguration> {
        every { defaultLocale } returns Locale.ENGLISH
    }
    private val factory =
        FeedMessageFactoryImpl(entityFactory, mockk<MarketFactory>(), producerManager, config)
    private val unmarshaller = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.feed.v1").createUnmarshaller()

    private fun xml(certainty: Int) =
        """<bet_settlement certainty="$certainty" product="1" event_id="od:match:1" timestamp="1700000000000"></bet_settlement>"""

    /** The real path: JAXB decodes the XML, the message factory wraps what came out. */
    private fun decode(certainty: Int): Pair<OFBetSettlement, BetSettlement<*>> {
        val xml = xml(certainty)
        val raw = unmarshaller.unmarshal(StringReader(xml)) as OFBetSettlement
        val built = factory.buildMessage<Match>(
            FeedMessage(
                raw,
                xml.toByteArray(),
                RoutingKeyInfo("hi.-.live.bet_settlement.1.od:match.1", null, URN.parse("od:match:1"), false),
                MessageTimestamp(0, 0, 0, 0)
            )
        )
        assertNotNull(built)
        assertTrue(built is BetSettlement<*>)
        return raw to (built as BetSettlement<*>)
    }

    @Test
    fun certaintyOneIsUnknown() {
        val (raw, message) = decode(1)
        assertEquals(1, raw.certainty) // the raw class still carries what was sent
        assertEquals(BetSettlementCertainty.UNKNOWN, message.certainty)
    }

    @Test
    fun certaintyTwoIsUnknown() {
        val (raw, message) = decode(2)
        assertEquals(2, raw.certainty)
        assertEquals(BetSettlementCertainty.UNKNOWN, message.certainty)
    }

    // Kotlin's @Deprecated is not a runtime annotation: the compiler writes the class-file
    // Deprecated attribute, which javac reads for Java callers (and reflection does not show).
    @Test
    fun theKotlinMembersCarryTheClassFileDeprecatedAttribute() {
        assertTrue(hasDeprecatedAttribute(BetSettlement::class.java, "getCertainty"))
        assertTrue(hasDeprecatedAttribute(BetSettlementImpl::class.java, "getCertainty"))
        assertFalse(hasDeprecatedAttribute(BetSettlement::class.java, "getMarkets"))
    }

    @Test
    fun theRawFeedClassMarksBothAccessorsDeprecated() {
        val deprecated = java.lang.Deprecated::class.java
        val getter = OFBetSettlement::class.java.getMethod("getCertainty")
        val setter = OFBetSettlement::class.java.getMethod("setCertainty", Int::class.javaPrimitiveType)
        assertTrue(getter.isAnnotationPresent(deprecated))
        assertTrue(setter.isAnnotationPresent(deprecated))
        assertTrue(hasDeprecatedAttribute(OFBetSettlement::class.java, "getCertainty"))
        assertTrue(hasDeprecatedAttribute(OFBetSettlement::class.java, "setCertainty"))
    }

    /** True when the class file marks the (first) method of that name with a Deprecated attribute. */
    private fun hasDeprecatedAttribute(type: Class<*>, method: String): Boolean {
        val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val data = DataInputStream(ByteArrayInputStream(bytes))
        data.readInt(); data.readUnsignedShort(); data.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(data.readUnsignedShort())
        var i = 1
        while (i < utf8.size) {
            when (val tag = data.readUnsignedByte()) {
                1 -> utf8[i] = data.readUTF()
                3, 4, 9, 10, 11, 12, 17, 18 -> data.skipBytes(4)
                5, 6 -> { data.skipBytes(8); i++ }
                7, 8, 16, 19, 20 -> data.skipBytes(2)
                15 -> data.skipBytes(3)
                else -> error("constant pool tag $tag")
            }
            i++
        }
        data.skipBytes(6)
        data.skipBytes(2 * data.readUnsignedShort())
        repeat(data.readUnsignedShort()) { // fields
            data.skipBytes(6); skipAttributes(data)
        }
        repeat(data.readUnsignedShort()) {
            data.skipBytes(2)
            val name = utf8[data.readUnsignedShort()]
            data.skipBytes(2)
            val attributes = mutableListOf<String?>()
            repeat(data.readUnsignedShort()) {
                attributes.add(utf8[data.readUnsignedShort()])
                data.skipBytes(data.readInt())
            }
            if (name == method) return "Deprecated" in attributes
        }
        error("no method $method in ${type.name}")
    }

    private fun skipAttributes(data: DataInputStream) {
        repeat(data.readUnsignedShort()) { data.skipBytes(2); data.skipBytes(data.readInt()) }
    }

    @Test
    fun theEnumIsNotDeprecated() {
        assertFalse(BetSettlementCertainty::class.java.isAnnotationPresent(java.lang.Deprecated::class.java))
    }
}
