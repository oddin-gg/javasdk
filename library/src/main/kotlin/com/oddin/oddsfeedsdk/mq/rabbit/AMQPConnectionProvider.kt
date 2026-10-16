package com.oddin.oddsfeedsdk.mq.rabbit

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.InitException
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.rabbitmq.client.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import javax.net.ssl.SSLContext


interface AMQPConnectionProvider {
    fun close()
    fun isConnectionOpen(): Boolean
    fun newChannel(): Channel
}

private val logger = KotlinLogging.logger {}

/**
 * TLS for the broker connection: the broker's certificate must chain to a CA the JVM trusts
 * (its default trust store, or the one named by `javax.net.ssl.trustStore`), and be issued for the
 * host connected to. `useSslProtocol()` without a context trusts any certificate and checks no
 * host name, which lets anyone on the path to the broker take the access token, sent as the
 * user name.
 */
internal fun ConnectionFactory.useCheckedTls(sslContext: SSLContext = SSLContext.getDefault()) {
    useSslProtocol(sslContext)
    enableHostnameVerification()
}

class SingleAMQPConnectionProvider @Inject constructor(
    private val rabbitConnectionFactory: ConnectionFactory,
    private val config: OddsFeedConfiguration,
    private val whoAmIManager: WhoAmIManager,
    private val globalEventsListener: GlobalEventsListener
) : AMQPConnectionProvider {
    private var connection: Connection? = null
    private val syncLock = Any()
    private val redactor = TokenRedactor(config.accessToken)
    private var tlsConfigured = false

    // The trust the connection is checked against: the JVM's default unless a test sets its own.
    // Internal on purpose, and nothing here can switch the check off.
    internal var sslContext: SSLContext? = null

    companion object {
        private const val VIRTUAL_HOST_PREFIX = "/oddinfeed"
    }

    override fun newChannel(): Channel {
        synchronized(syncLock) {
            try {
                if (connection == null) {
                    connection = makeConnection(rabbitConnectionFactory, config, whoAmIManager)
                }

                val channel = connection?.createChannel()
                    ?: throw IllegalStateException("Failed to create new AMQP connection")
                return withoutTokenInFailures(channel)
            } catch (e: Exception) {
                throw redactor.throwable(e)
            }
        }
    }

    // What a broker says when it refuses a channel operation names the user as well; the channel
    // is handed out through this, so whoever fails on it sees the failure without the token. The
    // channel's own description names the user too (amqp://<user>@host/vhost). equals and hashCode
    // are the proxy's own, as they are for any object.
    private fun withoutTokenInFailures(channel: Channel): Channel =
        Proxy.newProxyInstance(Channel::class.java.classLoader, arrayOf(Channel::class.java)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args[0]
                method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
                method.declaringClass == Any::class.java && method.name == "toString" -> redactor.text(channel.toString())
                else -> try {
                    method.invoke(channel, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw redactor.throwable(e.targetException)
                }
            }
        } as Channel

    private fun makeConnection(
        rabbitConnectionFactory: ConnectionFactory,
        config: OddsFeedConfiguration,
        whoAmIManager: WhoAmIManager
    ): Connection {
        logger.info("Creating new AMQP connection")

        rabbitConnectionFactory.host = config.selectedEnvironment.messagingHost
        rabbitConnectionFactory.port = config.selectedEnvironment.messagingPort
        // once: each call adds another host name check to the factory's socket configurator
        if (!tlsConfigured) {
            rabbitConnectionFactory.useCheckedTls(sslContext ?: SSLContext.getDefault())
            tlsConfigured = true
        }

        // Validate bookmaker again
        if (whoAmIManager.bookmakerDetail == null) {
            runBlocking {
                whoAmIManager.fetchBookmakerDetails()
            }
        }
        val bookmakerId =
            whoAmIManager.bookmakerDetail?.bookmakerId ?: throw InitException("Missing bookmaker id", null)

        val props =
            rabbitConnectionFactory.clientProperties
        props["SDK"] = "java"
        rabbitConnectionFactory.clientProperties = props
        rabbitConnectionFactory.username = config.accessToken
        rabbitConnectionFactory.virtualHost = "$VIRTUAL_HOST_PREFIX/${bookmakerId}"
        // Empty password
        rabbitConnectionFactory.password = ""
        rabbitConnectionFactory.isAutomaticRecoveryEnabled = true
        rabbitConnectionFactory.exceptionHandler = AMQPExceptionHandler()
        val connection = rabbitConnectionFactory.newConnection()
        logger.info { "AMQP connection created" }

        connection.addShutdownListener {
            try {
                globalEventsListener.onConnectionDown()
            } catch (e: Exception) {

            }

            if (it.isInitiatedByApplication) {
                logger.info { "AMQP connection shut down by application" }
            } else {
                logger.info { "AMQP connection shut down by: ${redactor.text(it.toString())}" }
            }
        }

        return connection
    }

    override fun close() {
        logger.info { "Closing AMQP connection" }

        try {
            connection?.close()
        } catch (e: Exception) {
            logger.debug { "AMQP connection failed to close ${redactor.text(e.toString())}" }
        } finally {
            connection = null
        }
    }

    override fun isConnectionOpen(): Boolean {
        return connection?.isOpen ?: false
    }

}

class AMQPExceptionHandler : ExceptionHandler {
    override fun handleBlockedListenerException(p0: Connection?, p1: Throwable?) {

    }

    override fun handleTopologyRecoveryException(p0: Connection?, p1: Channel?, p2: TopologyRecoveryException?) {

    }

    override fun handleConsumerException(p0: Channel?, p1: Throwable?, p2: Consumer?, p3: String?, p4: String?) {

    }

    override fun handleConnectionRecoveryException(p0: Connection?, p1: Throwable?) {

    }

    override fun handleUnexpectedConnectionDriverException(p0: Connection?, p1: Throwable?) {

    }

    override fun handleChannelRecoveryException(p0: Channel?, p1: Throwable?) {

    }

    override fun handleReturnListenerException(p0: Channel?, p1: Throwable?) {

    }

    override fun handleConfirmListenerException(p0: Channel?, p1: Throwable?) {

    }

}