package com.oddin.oddsfeedsdk

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.api.entities.ProducerData
import com.oddin.oddsfeedsdk.api.entities.ProducerImpl
import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.TimeUnit


interface ProducerManager {
    val availableProducers: Map<Long, Producer>

    val activeProducers: Map<Long, Producer>

    fun getProducer(id: Long): Producer?

    fun setProducerState(id: Long, enabled: Boolean)

    fun setProducerRecoveryFromTimestamp(producerId: Long, timestamp: Long)

    fun isProducerEnabled(id: Long): Boolean

    fun isProducerDown(id: Long): Boolean
}

interface SDKProducerManager : ProducerManager {
    suspend fun open()

    fun setProducerDown(id: Long, flaggedDown: Boolean)

    fun setProducerLastMessageTimestamp(id: Long, timestamp: Long)

    fun setLastProcessedMessageGenTimestamp(id: Long, timestamp: Long)

    fun setLastAliveReceivedGenTimestamp(id: Long, timestamp: Long)

    fun setProducerRecoveryInfo(id: Long, recoveryInfo: RecoveryInfo)
}


private val logger = KotlinLogging.logger {}

class ProducerManagerImpl @Inject constructor(
    private val apiClient: ApiClient,
    private val configuration: OddsFeedConfiguration
) :
    SDKProducerManager {
    private var _producers: Map<Long, ProducerData>? = null
    private val producers: Map<Long, ProducerData>
        get() {
            if (_producers == null) {
                runBlocking {
                    open()
                }
            }

            val producers = _producers
            requireNotNull(producers)
            return producers
        }

    override suspend fun open() {
        val producers = apiClient.fetchProducers()

        logger.debug { "Fetched producer list - size ${producers.producer.size}" }

        _producers = producers
            .producer
            .map {
                it.id to ProducerData(
                    id = it.id,
                    active = it.isActive,
                    apiUrl = it.apiUrl,
                    description = it.description,
                    name = it.name,
                    producerScopes = it.scope,
                    statefulRecoveryWindowInMinutes = it.statefulRecoveryWindowInMinutes
                )
            }.toMap()

        logger.debug { "Mapped producer list - ${_producers?.values}" }
    }

    override val availableProducers: Map<Long, Producer>
        get() = producers.values.map { it.id to ProducerImpl(it) }.toMap()

    override val activeProducers: Map<Long, Producer>
        get() = producers.values.filter { it.active }.map {
            it.id to ProducerImpl(
                it
            )
        }.toMap()

    override fun getProducer(id: Long): Producer? {
        val producerData = producers[id]
        return if (producerData == null) {
            logger.warn("Creating unknown producer: $id")
            ProducerImpl(id, configuration)
        } else {
            ProducerImpl(producerData)
        }
    }

    override fun setProducerState(id: Long, enabled: Boolean) {
        producers[id]?.enabled = enabled
    }

    override fun setProducerRecoveryFromTimestamp(producerId: Long, timestamp: Long) {
        // @TODO refactor
        val producer = producers[producerId] ?: return
        if (timestamp != 0L) {
            val maxRequestMinutes = producer.statefulRecoveryWindowInMinutes
            val maxRecoveryInterval =
                TimeUnit.MILLISECONDS.convert(maxRequestMinutes.toLong(), TimeUnit.MINUTES)
            val requestedRecoveryInterval = System.currentTimeMillis() - timestamp
            require(requestedRecoveryInterval <= maxRecoveryInterval) {
                String.format(
                    "Last received message timestamp can not be more than '%s' minutes ago, producerId:%s timestamp:%s (max recovery = '%s' minutes ago)",
                    maxRequestMinutes,
                    producerId,
                    timestamp,
                    maxRequestMinutes
                )
            }
        }

        producer.recoveryFromTimestamp = timestamp
    }

    override fun isProducerEnabled(id: Long): Boolean {
        return producers[id]?.enabled ?: false
    }

    override fun isProducerDown(id: Long): Boolean {
        return producers[id]?.flaggedDown ?: true
    }

    override fun setProducerDown(id: Long, flaggedDown: Boolean) {
        producers[id]?.flaggedDown = flaggedDown
    }

    override fun setProducerLastMessageTimestamp(id: Long, timestamp: Long) {
        require(timestamp > 0L)
        producers[id]?.lastMessageTimestamp = timestamp
    }

    override fun setLastProcessedMessageGenTimestamp(id: Long, timestamp: Long) {
        producers[id]?.lastProcessedMessageGenTimestamp = timestamp
    }

    override fun setLastAliveReceivedGenTimestamp(id: Long, timestamp: Long) {
        producers[id]?.lastAliveReceivedGenTimestamp = timestamp
    }

    override fun setProducerRecoveryInfo(id: Long, recoveryInfo: RecoveryInfo) {
        producers[id]?.lastRecoveryInfo = recoveryInfo
    }
}