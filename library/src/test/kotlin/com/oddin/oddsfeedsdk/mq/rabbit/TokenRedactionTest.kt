package com.oddin.oddsfeedsdk.mq.rabbit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.oddin.oddsfeedsdk.api.BookmakerDetail
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import com.oddin.oddsfeedsdk.config.Environment
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.ShutdownListener
import com.rabbitmq.client.ShutdownSignalException
import com.rabbitmq.client.impl.AMQImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.IOException

// The access token is the AMQP user name, and the broker's refusals name the user. What the SDK
// logs or throws of a broker failure must not carry it, and must otherwise read as it did.
class TokenRedactionTest {

    private val token = "tok-3f9c1d"

    private val refusal = "NOT_ALLOWED - access to vhost '/oddinfeed/1' refused for user '$token'"

    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var level: Level? = null

    @Before
    fun readTheLog() {
        level = root.level
        root.level = Level.DEBUG
        appender.start()
        root.addAppender(appender)
    }

    @After
    fun stopReadingTheLog() {
        root.detachAppender(appender)
        appender.stop()
        root.level = level
    }

    private fun logged() = appender.list.map { it.formattedMessage }

    private fun signal(text: String) =
        ShutdownSignalException(true, false, AMQImpl.Connection.Close(530, text, 0, 0), null)

    private fun provider(
        connection: Connection? = null,
        newConnectionFails: Exception? = null
    ): SingleAMQPConnectionProvider {
        val factory = object : ConnectionFactory() {
            override fun newConnection(): Connection {
                newConnectionFails?.let { throw it }
                return connection!!
            }
        }
        val config = mockk<OddsFeedConfiguration> {
            every { accessToken } returns token
            every { selectedEnvironment } returns Environment("127.0.0.1", "api.test", 5671)
        }
        val whoAmI = mockk<WhoAmIManager> {
            every { bookmakerDetail } returns mockk<BookmakerDetail> { every { bookmakerId } returns 1 }
        }
        return SingleAMQPConnectionProvider(factory, config, whoAmI, mockk<GlobalEventsListener>(relaxed = true))
    }

    /** The provider has opened [connection]; this is the listener it put on it. */
    private fun shutdownListenerOf(connection: Connection): ShutdownListener {
        val listener = slot<ShutdownListener>()
        every { connection.addShutdownListener(capture(listener)) } returns Unit
        provider(connection).newChannel()
        return listener.captured
    }

    private fun connectionWith(channel: Channel = mockk(relaxed = true)): Connection =
        mockk(relaxed = true) { every { createChannel() } returns channel }

    private fun assertNoToken(failure: Throwable) {
        generateSequence(failure) { it.cause }.forEach {
            assertFalse("the token is in ${it.javaClass.name}: $it", it.toString().contains(token))
        }
    }

    @Test
    fun aShutdownSignalNamingTheTokenIsLoggedWithoutIt() {
        shutdownListenerOf(connectionWith()).shutdownCompleted(signal(refusal))

        val line = logged().single { it.startsWith("AMQP connection shut down by") }
        assertFalse(line, line.contains(token))
        assertTrue(line, line.contains("refused for user '${TokenRedactor.MARKER}'"))
        assertTrue(line, line.contains("reply-code=530"))
    }

    @Test
    fun aShutdownSignalWithoutTheTokenIsLoggedAsItIs() {
        val signal = signal("CONNECTION_FORCED - broker forced connection closure with reason 'shutdown'")

        shutdownListenerOf(connectionWith()).shutdownCompleted(signal)

        assertEquals("AMQP connection shut down by: $signal", logged().single { it.startsWith("AMQP connection shut") })
    }

    @Test
    fun aFailureToCloseNamingTheTokenIsLoggedWithoutIt() {
        val connection = connectionWith()
        every { connection.close() } throws IOException(refusal)
        val provider = provider(connection)
        provider.newChannel()

        provider.close()

        val line = logged().single { it.startsWith("AMQP connection failed to close") }
        assertFalse(line, line.contains(token))
        assertTrue(line, line.contains("refused for user '${TokenRedactor.MARKER}'"))
    }

    @Test
    fun aConnectionFailureNamingTheTokenIsThrownWithoutItAndKeepsItsTrace() {
        val inner = IOException("socket closed")
        val original = IOException("connection refused: $refusal", signal(refusal).also { it.initCause(inner) })
        val provider = provider(newConnectionFails = original)

        try {
            provider.newChannel()
            fail("the connection failure was swallowed")
        } catch (e: Exception) {
            assertNoToken(e)
            assertTrue(e.toString(), e.toString().startsWith("java.io.IOException: connection refused: "))
            assertTrue(e.toString(), e.toString().contains("refused for user '${TokenRedactor.MARKER}'"))
            assertArrayEquals(original.stackTrace, e.stackTrace)
            // the signal named the token as well; what it carries that does not stays what it was
            val signal = e.cause!!
            assertTrue(signal.toString(), signal.toString().startsWith("com.rabbitmq.client.ShutdownSignalException"))
            assertSame(inner, signal.cause)
        }
    }

    @Test
    fun theTokenInsideALongerTextIsRedactedToo() {
        val provider = provider(newConnectionFails = IOException("user 'a${token}b' and $token again"))

        try {
            provider.newChannel()
            fail("the connection failure was swallowed")
        } catch (e: Exception) {
            assertEquals(
                "java.io.IOException: user 'a${TokenRedactor.MARKER}b' and ${TokenRedactor.MARKER} again",
                e.toString()
            )
        }
    }

    @Test
    fun aConnectionFailureWithoutTheTokenIsThrownAsItIs() {
        val original = IOException("Connection refused", IOException("no route"))

        try {
            provider(newConnectionFails = original).newChannel()
            fail("the connection failure was swallowed")
        } catch (e: Exception) {
            assertSame(original, e)
        }
    }

    @Test
    fun aRefusedChannelOperationIsThrownWithoutTheToken() {
        val original = IOException("ACCESS_REFUSED - access to exchange 'oddinfeed' refused for user '$token'")
        val channel = mockk<Channel>(relaxed = true) {
            every { queueBind(any(), any(), any()) } throws original
        }

        val handedOut = provider(connectionWith(channel)).newChannel()

        try {
            handedOut.queueBind("queue", "oddinfeed", "#")
            fail("the refusal was swallowed")
        } catch (e: Exception) {
            assertNoToken(e)
            assertTrue(e.toString(), e.toString().contains("refused for user '${TokenRedactor.MARKER}'"))
            assertArrayEquals(original.stackTrace, e.stackTrace)
        }
    }

    @Test
    fun theChannelHandedOutStillWorksAndFailsAsBefore() {
        val declared = mockk<AMQP.Queue.DeclareOk> { every { queue } returns "amq.gen-1" }
        val plain = IOException("channel is already closed")
        val channel = mockk<Channel>(relaxed = true) {
            every { queueDeclare() } returns declared
            every { basicConsume(any<String>(), any<Boolean>(), any<com.rabbitmq.client.Consumer>()) } throws plain
        }

        val handedOut = provider(connectionWith(channel)).newChannel()

        assertSame(declared, handedOut.queueDeclare())
        try {
            handedOut.basicConsume("amq.gen-1", true, mockk(relaxed = true))
            fail("the failure was swallowed")
        } catch (e: IOException) {
            assertSame(plain, e)
        }
    }
}
