package com.oddin.oddsfeedsdk.utils

import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.RawFeedMessage
import com.oddin.oddsfeedsdk.api.Deserializer
import com.oddin.oddsfeedsdk.mq.ChannelConsumerImpl
import com.oddin.oddsfeedsdk.mq.ExchangeProviderImpl
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.rabbit.AMQPConnectionProvider
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive
import com.oddin.oddsfeedsdk.schema.rest.v1.RAError
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.Envelope
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.bind.JAXBException
import kotlin.concurrent.thread

// A message or a response may declare a DTD, and a default unmarshaller may follow it: an external
// entity reads a local file, an external DTD is fetched from an address the sender chose. Both
// the feed consumer and the REST deserializer read through SafeXml, which turns that off.
//
// The JDK's JAXB already refuses external access when nothing overrides it: it asks the parser for
// secure processing, which sets javax.xml.accessExternalDTD to "". Anything that lifts that, the
// system property below or a parser such as Xerces on the classpath, opens the reads up, so the
// tests run with the property lifted, as a deployment that sets it would.
//
// What shows that an entity was read: the feed message decodes (JAXB drops the text the entity
// stands for, but a refused entity fails the whole message), and the REST error carries the
// file's text as its message.
class ExternalEntitiesTest {

    private companion object {
        const val ACCESS_EXTERNAL_DTD = "javax.xml.accessExternalDTD"
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val consumerSlot = slot<Consumer>()
    private val published = mutableListOf<Any>()

    // what asks for a DTD at "http://127.0.0.1:<port>/m.dtd" lands here
    private lateinit var dtdServer: ServerSocket
    private val dtdRequests = AtomicInteger()

    private var accessExternalDtd: String? = null

    @Before
    fun liftTheJdkRestriction() {
        accessExternalDtd = System.getProperty(ACCESS_EXTERNAL_DTD)
        System.setProperty(ACCESS_EXTERNAL_DTD, "all")
    }

    @After
    fun restoreTheJdkRestriction() {
        val before = accessExternalDtd
        if (before == null) System.clearProperty(ACCESS_EXTERNAL_DTD) else System.setProperty(ACCESS_EXTERNAL_DTD, before)
    }

    @Before
    fun startDtdServer() {
        dtdServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "dtd-server") {
            try {
                while (true) {
                    dtdServer.accept().use { socket ->
                        dtdRequests.incrementAndGet()
                        // an empty DTD, so a parser that did ask goes on and the test sees the request
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                        )
                    }
                }
            } catch (e: Exception) {
                // closed by the test
            }
        }
    }

    @After
    fun stopDtdServer() {
        dtdServer.close()
    }

    private fun entityFile(content: String): String {
        val file = folder.newFile("entity.txt")
        file.writeText(content)
        return file.toURI().toString()
    }

    private val dtdUrl: String
        get() = "http://127.0.0.1:${dtdServer.localPort}/m.dtd"

    private fun deliver(body: String) {
        val channel = mockk<Channel>(relaxed = true) {
            every { queueDeclare() } returns mockk(relaxed = true) {
                every { queue } returns "test-queue"
            }
            every { basicConsume(any<String>(), any<Boolean>(), capture(consumerSlot)) } returns "tag"
        }
        val channelProvider = mockk<AMQPConnectionProvider> {
            every { newChannel() } returns channel
        }
        val dispatchManager = mockk<DispatchManager> {
            every { publish(any()) } answers { published.add(firstArg()); Unit }
        }
        ChannelConsumerImpl(channelProvider, mockk(relaxed = true))
            .open(listOf("#"), MessageInterest.ALL, dispatchManager, ExchangeProviderImpl())

        consumerSlot.captured.handleDelivery(
            "tag",
            Envelope(1L, false, "oddinfeed", "hi.-.-.alive.-.-.-"),
            AMQP.BasicProperties.Builder().build(),
            body.toByteArray()
        )
    }

    private fun parsedMessages() = published.filterIsInstance<RawFeedMessage>()

    private fun restError(body: String): RAError? =
        Deserializer<RAError>().deserialize(ByteArrayInputStream(body.toByteArray()))

    @Test
    fun feedMessageDoesNotReadAFileThroughAnEntity() {
        val file = entityFile("secret content")
        deliver(
            """<!DOCTYPE alive [<!ENTITY x SYSTEM "$file">]>
               <alive product="1" timestamp="1" subscribed="1">&x;</alive>"""
        )

        assertTrue("the entity was resolved", parsedMessages().isEmpty())
    }

    @Test
    fun feedMessageDoesNotFetchItsDtd() {
        deliver("""<!DOCTYPE alive SYSTEM "$dtdUrl"><alive product="1" timestamp="1" subscribed="1"/>""")

        assertEquals("the DTD was fetched", 0, dtdRequests.get())
    }

    @Test
    fun validFeedMessageStillParses() {
        deliver("""<alive product="1" timestamp="42" subscribed="1"/>""")

        val alive = parsedMessages().single().message as OFAlive
        assertEquals(1, alive.getProduct())
        assertEquals(42L, alive.getTimestamp())
    }

    @Test
    fun restResponseDoesNotReadAFileThroughAnEntity() {
        val file = entityFile("secret content")
        val body = """<!DOCTYPE response [<!ENTITY x SYSTEM "$file">]>
                      <response response_code="NOT_FOUND"><message>&x;</message></response>"""
        try {
            val error = restError(body)
            fail("the entity was resolved: ${error?.message}")
        } catch (e: JAXBException) {
            // refused
        }
    }

    @Test
    fun restResponseDoesNotFetchItsDtd() {
        restError("""<!DOCTYPE response SYSTEM "$dtdUrl"><response response_code="NOT_FOUND"/>""")

        assertEquals("the DTD was fetched", 0, dtdRequests.get())
    }

    @Test
    fun validRestResponseStillParses() {
        val error = restError("""<response response_code="NOT_FOUND"><message>no such match</message></response>""")

        assertEquals("no such match", error?.message?.message)
    }
}
