package com.oddin.oddsfeedsdk.config

import java.util.*

class OddsFeedConfiguration internal constructor(
    val accessToken: String,
    val defaultLocale: Locale,
    val maxInactivitySeconds: Int,
    val maxRecoveryExecutionMinutes: Int,
    val messagingPort: Int,
    val sdkNodeId: Int?,
    val exceptionHandlingStrategy: ExceptionHandlingStrategy,
    val selectedEnvironment: Environment
)

class OddsFeedConfigurationBuilder internal constructor() {
    private var accessToken: String? = null

    private var defaultLocale = Locale.ENGLISH

    private var selectedEnvironment: Environment? = null

    private var messagingPort: Int = 5672

    private var maxInactivitySeconds: Int = 20
    private var maxRecoveryExecutionMinutes: Int = 360

    private var sdkNodeId: Int? = null

    private var exceptionHandlingStrategy = ExceptionHandlingStrategy.THROW


    fun selectProduction() = apply {
        selectedEnvironment = Environment.PRODUCTION
    }

    fun selectIntegration() = apply {
        selectedEnvironment = Environment.INTEGRATION
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
            messagingPort = messagingPort,
            sdkNodeId = sdkNodeId,
            selectedEnvironment = environment
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

enum class Environment(val messagingHost: String, val apiHost: String) {
    PRODUCTION("mq.oddin.gg", "api-mq.oddin.gg"),
    INTEGRATION("mq.oddin.gg", "api-mq.oddin.gg")
}
