package com.oddin.oddsfeedsdk.mq.rabbit

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.InitException
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.rabbitmq.client.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.Executors


interface AMQPConnectionProvider {
    fun close()
    fun isConnectionOpen(): Boolean
    fun newChannel(): Channel
}

private val logger = KotlinLogging.logger {}

class SingleAMQPConnectionProvider @Inject constructor(
    private val rabbitConnectionFactory: ConnectionFactory,
    private val config: OddsFeedConfiguration,
    private val whoAmIManager: WhoAmIManager,
    private val globalEventsListener: GlobalEventsListener
) : AMQPConnectionProvider {
    private var connection: Connection? = null
    private val syncLock = Any()

    companion object {
        private const val VIRTUAL_HOST_PREFIX = "/oddinfeed"
    }

    override fun newChannel(): Channel {
        synchronized(syncLock) {
            if (connection == null) {
                connection = makeConnection(rabbitConnectionFactory, config, whoAmIManager)
            }

            return connection?.createChannel() ?: throw IllegalStateException("Failed to create new AMQP connection")
        }
    }

    private fun makeConnection(
        rabbitConnectionFactory: ConnectionFactory,
        config: OddsFeedConfiguration,
        whoAmIManager: WhoAmIManager
    ): Connection {
        logger.info("Creating new AMQP connection")

        rabbitConnectionFactory.host = config.selectedEnvironment.messagingHost
        rabbitConnectionFactory.port = config.messagingPort
        rabbitConnectionFactory.useSslProtocol()

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
                logger.info { "AMQP connection shut down by: $it" }
            }
        }

        return connection
    }

    override fun close() {
        logger.info { "Closing AMQP connection" }

        try {
            connection?.close()
        } catch (e: Exception) {
            logger.debug { "AMQP connection failed to close $e" }
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