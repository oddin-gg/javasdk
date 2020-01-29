package com.oddin.oddsfeedsdk.api.entities

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration

interface RecoveryInfo {
    val after: Long
    val timestamp: Long
    val requestId: Long
    val successful: Boolean
    val nodeId: Int?
}

data class RecoveryInfoImpl(
    override val after: Long,
    override val timestamp: Long,
    override val requestId: Long,
    override val nodeId: Int?,
    override val successful: Boolean
) : RecoveryInfo

enum class ProducerScope {
    LIVE, PREMATCH
}

interface Producer {
    val id: Long
    val name: String
    val description: String
    val lastMessageTimestamp: Long
    val isAvailable: Boolean
    val isEnabled: Boolean
    val isFlaggedDown: Boolean
    val apiUrl: String
    val producerScopes: Set<ProducerScope>
    val lastProcessedMessageGenTimestamp: Long
    val processingQueDelay: Long
    val timestampForRecovery: Long
    val statefulRecoveryWindowInMinutes: Int
    val recoveryInfo: RecoveryInfo?
}

class ProducerData(
    val id: Long,
    val name: String,
    val description: String,
    val active: Boolean,
    val apiUrl: String,
    producerScopes: String,
    val statefulRecoveryWindowInMinutes: Int
) {
    val producerScopes: Set<ProducerScope>
    var lastMessageTimestamp = 0L
    var enabled = active
    var flaggedDown = true
    var lastProcessedMessageGenTimestamp = 0L
    var lastAliveReceivedGenTimestamp = 0L
    var recoveryFromTimestamp: Long = 0
    var lastRecoveryInfo: RecoveryInfo? = null

    companion object {
        private val scopeMappings: Map<String, ProducerScope> =
            mapOf("prematch" to ProducerScope.PREMATCH, "live" to ProducerScope.LIVE)
    }

    init {
        this.producerScopes = producerScopes
            .split("\\|")
            .mapNotNull {
                scopeMappings[it]
            }
            .toSet()
    }

    override fun toString(): String {
        return "$id-$name"
    }
}

class ProducerImpl : Producer {
    private var producerData: ProducerData? = null
    val active: Boolean
    private val enabled: Boolean

    override val id: Long

    override val name: String

    override val description: String

    override val lastMessageTimestamp: Long
        get() = producerData?.lastMessageTimestamp ?: 0

    override val isAvailable: Boolean
        get() = active

    override val isEnabled: Boolean
        get() = enabled

    override val isFlaggedDown: Boolean
        get() = producerData?.flaggedDown ?: true

    override val apiUrl: String

    override val producerScopes: Set<ProducerScope>

    override val lastProcessedMessageGenTimestamp: Long
        get() = producerData?.lastProcessedMessageGenTimestamp ?: 0

    override val processingQueDelay: Long
        get() = System.currentTimeMillis() - lastProcessedMessageGenTimestamp

    override val timestampForRecovery: Long
        get() = producerData?.recoveryFromTimestamp ?: 0

    override val statefulRecoveryWindowInMinutes: Int

    override val recoveryInfo: RecoveryInfo? = null

    constructor(producerData: ProducerData) {
        this.producerData = producerData
        id = producerData.id
        name = producerData.name
        active = producerData.active
        enabled = producerData.enabled
        description = producerData.description
        apiUrl = producerData.apiUrl
        producerScopes = producerData.producerScopes
        statefulRecoveryWindowInMinutes = producerData.statefulRecoveryWindowInMinutes
    }

    constructor(unknownProducerId: Long, configuration: OddsFeedConfiguration) {
        id = unknownProducerId
        name = "unknown"
        active = true
        enabled = true
        description = "unknown producer"
        apiUrl = configuration.selectedEnvironment.apiHost
        producerScopes = ProducerScope.values().toSet()
        statefulRecoveryWindowInMinutes = STATEFUL_RECOVERY_MINUTES
    }

    companion object {
        private const val STATEFUL_RECOVERY_MINUTES = 4320
    }

}

