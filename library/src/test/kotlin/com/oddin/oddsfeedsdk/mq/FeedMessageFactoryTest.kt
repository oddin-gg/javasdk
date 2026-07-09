package com.oddin.oddsfeedsdk.mq

import com.oddin.oddsfeedsdk.FeedMessage
import com.oddin.oddsfeedsdk.SDKProducerManager
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match
import com.oddin.oddsfeedsdk.api.factories.EntityFactory
import com.oddin.oddsfeedsdk.api.factories.MarketFactory
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.mq.entities.OddsChange
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// CORE-3811: messages whose routing key has a blank sport slot ("-") must be
// delivered, not dropped — the match entity resolves its sport lazily.
class FeedMessageFactoryTest {

    private val entityFactory = mockk<EntityFactory> {
        every { buildMatch(any(), any(), isNull()) } returns mockk<Match>()
    }
    private val producerManager = mockk<SDKProducerManager> {
        every { getProducer(any<Long>()) } returns mockk()
    }
    private val config = mockk<OddsFeedConfiguration> {
        every { defaultLocale } returns Locale.ENGLISH
    }
    private val factory =
        FeedMessageFactoryImpl(entityFactory, mockk<MarketFactory>(), producerManager, config)

    private fun blankSportMessage(eventUrn: String): FeedMessage {
        val message = OFOddsChange()
        message.setProduct(1)
        return FeedMessage(
            message,
            ByteArray(1),
            RoutingKeyInfo("hi.pre.-.odds_change.-.od:match.2886418", null, URN.parse(eventUrn), false),
            MessageTimestamp(0, 0, 0, 0)
        )
    }

    @Test
    fun matchMessageWithBlankSportSlotIsDelivered() {
        val built = factory.buildMessage<Match>(blankSportMessage("od:match:2886418"))
        assertNotNull(built)
        assertTrue(built is OddsChange<*>)
    }

    @Test
    fun unparsableMessageWithBlankSportSlotIsBuilt() {
        val built = factory.buildUnparsableMessage<Match>(blankSportMessage("od:match:2886418"))
        assertNotNull(built)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tournamentMessageWithoutSportStillFails() {
        factory.buildMessage<Match>(blankSportMessage("od:tournament:123"))
    }
}
