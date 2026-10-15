package com.oddin.oddsfeedsdk.mq.rabbit

import com.oddin.oddsfeedsdk.api.BookmakerDetail
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import com.oddin.oddsfeedsdk.config.Environment
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.rabbitmq.client.ConnectionFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

// The broker connection checks the certificate and the host name. A local TLS server stands in
// for the broker; what each test looks at is whether the client's handshake with it succeeds.
//
// The keystores in src/test/resources/tls (password "changeit", valid for a hundred years) were
// made with the JDK's keytool, one self-signed certificate each:
//
//   keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 36500 \
//     -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" \
//     -storetype PKCS12 -keystore localhost.p12 -storepass changeit -keypass changeit
//   keytool -genkeypair -alias other-host -keyalg RSA -keysize 2048 -validity 36500 \
//     -dname "CN=other-host.example" -ext "SAN=dns:other-host.example" \
//     -storetype PKCS12 -keystore other-host.p12 -storepass changeit -keypass changeit
//
// "localhost" is issued for the address the tests connect to, "other-host" for another name.
// Neither is in the JVM's trust store.
class AMQPConnectionTlsTest {

    private val password = "changeit".toCharArray()

    private fun keystore(name: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            javaClass.getResourceAsStream("/tls/$name.p12").use { load(it, password) }
        }

    /** The context a client trusting exactly the certificate of [name] would have. */
    private fun trusting(name: String): SSLContext {
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry(name, keystore(name).getCertificate(name))
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(trust)
        return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
    }

    /**
     * A broker that is only a TLS endpoint: it answers a handshake with the certificate of [name]
     * and says whether the handshake with the client completed. What follows it is not AMQP.
     */
    private inner class TlsServer(name: String) : AutoCloseable {
        private val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keystore(name), password) }
        private val socket = SSLContext.getInstance("TLS")
            .apply { init(keys.keyManagers, null, null) }
            .serverSocketFactory
            .createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

        val port: Int get() = socket.localPort

        // one entry per client that came: whether its handshake completed
        private val attempts = LinkedBlockingQueue<Boolean>()

        init {
            thread(isDaemon = true, name = "tls-server") {
                while (!socket.isClosed) {
                    var completed = false
                    try {
                        (socket.accept() as SSLSocket).use {
                            it.soTimeout = 10_000
                            it.startHandshake()
                            completed = true
                            // the client's AMQP protocol header, then the connection is dropped
                            it.getInputStream().read()
                        }
                    } catch (e: Exception) {
                        // a client that refuses the certificate ends the handshake here
                        if (socket.isClosed) {
                            return@thread
                        }
                    }
                    attempts.add(completed)
                }
            }
        }

        /** What happened to the next [count] clients, once the server is done with them. */
        fun handshakes(count: Int): List<Boolean> =
            (1..count).map {
                attempts.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("the server saw only ${it - 1} of $count clients")
            }

        /** Whether the client finished the handshake; waits for the server to be done with it. */
        fun handshakeCompleted(): Boolean = handshakes(1).single()

        override fun close() = socket.close()
    }

    private val servers = mutableListOf<TlsServer>()

    private fun server(name: String) = TlsServer(name).also { servers.add(it) }

    @After
    fun closeServers() {
        servers.forEach { it.close() }
    }

    private fun connect(factory: ConnectionFactory, port: Int): Throwable? {
        factory.host = "127.0.0.1"
        factory.port = port
        factory.connectionTimeout = 10_000
        return try {
            factory.newConnection().close()
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun certificateFailure(e: Throwable?): Throwable? =
        generateSequence(e) { it.cause }.firstOrNull { it is CertificateException }

    private fun provider(
        port: Int,
        trust: SSLContext? = null,
        factory: ConnectionFactory = ConnectionFactory()
    ): SingleAMQPConnectionProvider {
        val config = mockk<OddsFeedConfiguration> {
            every { accessToken } returns "token"
            every { selectedEnvironment } returns Environment("127.0.0.1", "api.test", port)
        }
        val whoAmI = mockk<WhoAmIManager> {
            every { bookmakerDetail } returns mockk<BookmakerDetail> { every { bookmakerId } returns 1 }
        }
        return SingleAMQPConnectionProvider(
            factory,
            config,
            whoAmI,
            mockk<GlobalEventsListener>(relaxed = true)
        ).also { it.sslContext = trust }
    }

    @Test(timeout = 60_000)
    fun theProviderRefusesACertificateTheJvmDoesNotTrust() {
        val broker = server("localhost")

        try {
            provider(broker.port).newChannel()
            fail("the connection was made to a broker with a certificate nobody vouches for")
        } catch (e: SSLException) {
            assertNotNull("not a certificate failure: $e", certificateFailure(e))
        }
        assertFalse("the handshake completed", broker.handshakeCompleted())
    }

    @Test(timeout = 60_000)
    fun aTrustedCertificateIssuedForThatHostIsAccepted() {
        val broker = server("localhost")
        val factory = ConnectionFactory().apply { useCheckedTls(trusting("localhost")) }

        val failure = connect(factory, broker.port)

        // the handshake is done; what fails is the AMQP protocol the test server does not speak
        assertTrue("the handshake completed", broker.handshakeCompleted())
        assertEquals("a certificate failure: $failure", null, certificateFailure(failure))
    }

    @Test(timeout = 60_000)
    fun aTrustedCertificateIssuedForAnotherHostIsRefused() {
        val broker = server("other-host")
        val factory = ConnectionFactory().apply { useCheckedTls(trusting("other-host")) }

        val failure = connect(factory, broker.port)

        assertTrue("the connection was not refused: $failure", failure is SSLException)
        assertNotNull("not a host name failure: $failure", certificateFailure(failure))
        assertFalse("the handshake completed", broker.handshakeCompleted())
    }

    @Test(timeout = 60_000)
    fun theProviderRefusesATrustedCertificateIssuedForAnotherHost() {
        val broker = server("other-host")

        try {
            provider(broker.port, trusting("other-host")).newChannel()
            fail("the connection was made to a host the certificate was not issued for")
        } catch (e: SSLException) {
            assertNotNull("not a host name failure: $e", certificateFailure(e))
        }
        assertFalse("the handshake completed", broker.handshakeCompleted())
    }

    @Test(timeout = 60_000)
    fun theProviderGetsPastTheHandshakeWithACertificateIssuedForTheHost() {
        val broker = server("localhost")

        val failure = try {
            provider(broker.port, trusting("localhost")).newChannel()
            null
        } catch (e: Exception) {
            e
        }

        // what fails is the AMQP protocol the test server does not speak
        assertTrue("the handshake did not complete: $failure", broker.handshakeCompleted())
        assertEquals("a certificate failure: $failure", null, certificateFailure(failure))
    }

    // The JVM's default context is what every real connection uses. A certificate the default trust
    // store would vouch for cannot be had here, so the default is replaced for the length of the test
    // by a context that trusts the other-host certificate: Gradle runs the test classes of this
    // module one after the other in a single JVM (no parallel forks, no JUnit parallelism), so
    // nothing else reads the default meanwhile.
    @Test(timeout = 60_000)
    fun theProviderUsingTheDefaultContextRefusesATrustedCertificateIssuedForAnotherHost() {
        val broker = server("other-host")
        val before = SSLContext.getDefault()
        SSLContext.setDefault(trusting("other-host"))
        try {
            provider(broker.port).newChannel()
            fail("the connection was made to a host the certificate was not issued for")
        } catch (e: SSLException) {
            assertNotNull("not a host name failure: $e", certificateFailure(e))
        } finally {
            SSLContext.setDefault(before)
        }
        assertFalse("the handshake completed", broker.handshakeCompleted())
    }

    @Test(timeout = 60_000)
    fun theChecksStayInForceWhenTheSameProviderConnectsAgain() {
        val broker = server("other-host")
        var hostNameChecks = 0
        val factory = object : ConnectionFactory() {
            override fun enableHostnameVerification() {
                hostNameChecks++
                super.enableHostnameVerification()
            }
        }
        val provider = provider(broker.port, trusting("other-host"), factory)

        repeat(2) {
            try {
                provider.newChannel()
                fail("the connection was made to a host the certificate was not issued for")
            } catch (e: SSLException) {
                assertNotNull("not a host name failure: $e", certificateFailure(e))
            }
        }

        assertEquals("no handshake completes", listOf(false, false), broker.handshakes(2))
        assertEquals("the host name check is set up once", 1, hostNameChecks)
    }
}
