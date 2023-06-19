package com.oddin.oddsfeedsdk.config

import java.time.Duration
import java.util.*

class OddsFeedConfiguration internal constructor(
    val accessToken: String,
    val defaultLocale: Locale,
    val maxInactivitySeconds: Int,
    val maxRecoveryExecutionMinutes: Int,
    val sdkNodeId: Int?,
    val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    val selectedEnvironment: Environment,
    val initialSnapshotRecoveryInterval: Duration?,
)

class OddsFeedConfigurationBuilder internal constructor() {
    private var accessToken: String? = null

    private var defaultLocale = Locale.ENGLISH

    private var selectedEnvironment: Environment? = null

    private val defaultMessagingPort: Int = 5672

    private var maxInactivitySeconds: Int = 20
    private var maxRecoveryExecutionMinutes: Int = 360

    private var sdkNodeId: Int? = null

    private var exceptionHandlingStrategy = ExceptionHandlingStrategy.THROW

    private var initialSnapshotRecoveryInterval: Duration? = null

    fun selectProduction() = apply {
        selectProduction(Region.DEFAULT)
    }

    fun selectProduction(region: Region) = apply {
        val mqHost =  "mq." + region.host + "oddin.gg"
        val apiHost = "api-mq."+ region.host + "oddin.gg"
        selectedEnvironment = Environment(mqHost, apiHost, defaultMessagingPort)
    }

    fun selectIntegration() = apply {
        selectIntegration(Region.DEFAULT)
    }

    fun selectIntegration(region: Region) = apply {
        val mqHost =  "mq.integration."+ region.host + "oddin.gg"
        val apiHost = "api-mq.integration."+ region.host + "oddin.gg"
        selectedEnvironment = Environment(mqHost, apiHost, defaultMessagingPort)
    }

    fun selectTest() = apply {
        selectTest(Region.DEFAULT)
    }

    fun selectTest(region: Region) = apply {
        val mqHost =  "mq-test.integration."+ region.host +"oddin.gg"
        val apiHost = "api-mq-test.integration."+ region.host +"oddin.gg"
        selectedEnvironment = Environment(mqHost, apiHost, defaultMessagingPort)
    }

    fun selectEnvironment(messagingHost: String, apiHost: String) = apply {
        selectedEnvironment = Environment(messagingHost, apiHost, defaultMessagingPort)
    }

    fun selectEnvironment(messagingHost: String, apiHost: String, messagingPort: Int) = apply {
        selectedEnvironment = Environment(messagingHost, apiHost, messagingPort)
    }

    fun setAccessToken(accessToken: String) = apply {
        this.accessToken = accessToken
    }

    fun setSDKNodeId(sdkNodeId: Int) = apply {
        this.sdkNodeId = sdkNodeId
    }

    fun setExceptionHandlingStrategy(exceptionHandlingStrategy: ExceptionHandlingStrategy) = apply {
        this.exceptionHandlingStrategy = exceptionHandlingStrategy
    }

    fun setInitialSnapshotRecoveryInterval(interval: Duration) = apply {
        this.initialSnapshotRecoveryInterval = interval
    }

    @Throws(IllegalArgumentException::class)
    fun build(): OddsFeedConfiguration {
        val token = accessToken ?: throw IllegalArgumentException("Missing access token. Please set access token.")
        val environment =
            selectedEnvironment ?: throw IllegalArgumentException("Missing environment. Please select environment.")

        return OddsFeedConfiguration(
            accessToken = token,
            defaultLocale = defaultLocale,
            exceptionHandlingStrategy = exceptionHandlingStrategy,
            maxInactivitySeconds = maxInactivitySeconds,
            maxRecoveryExecutionMinutes = maxRecoveryExecutionMinutes,
            sdkNodeId = sdkNodeId,
            selectedEnvironment = environment,
            initialSnapshotRecoveryInterval = initialSnapshotRecoveryInterval,
        )
    }

}

enum class ExceptionHandlingStrategy {
    THROW,
    CATCH
}

interface ExceptionHandler {
    val exceptionHandlingStrategy: ExceptionHandlingStrategy

    fun <T> wrapError(callable: () -> T, type: String): T? {
        return try {
            callable()
        } catch (e: Exception) {
            if (exceptionHandlingStrategy == ExceptionHandlingStrategy.THROW) {
                throw e
            } else {
                null
            }
        }
    }
}

data class Environment(val messagingHost: String, val apiHost: String, val messagingPort: Int)

enum class Region(val host: String) {
    DEFAULT(""),
    AP_SOUTHEAST_1("ap-southeast-1.")
}
