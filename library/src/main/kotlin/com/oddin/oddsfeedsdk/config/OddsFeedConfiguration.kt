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
    // Maximum number of entries held by each in-memory cache, on top of the
    // time-based expiry. Bounds memory under large schedules; over-capacity
    // entries are evicted least-recently-used and transparently re-fetched on
    // next read [CORE-3581].
    val maxMatchCacheSize: Long,
    val maxFixtureCacheSize: Long,
    val maxCompetitorCacheSize: Long,
    val maxPlayerCacheSize: Long,
) {
    companion object {
        const val DEFAULT_MAX_MATCH_CACHE_SIZE = 10_000L
        const val DEFAULT_MAX_FIXTURE_CACHE_SIZE = 10_000L
        const val DEFAULT_MAX_COMPETITOR_CACHE_SIZE = 20_000L
        const val DEFAULT_MAX_PLAYER_CACHE_SIZE = 50_000L
    }
}

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

    private var maxMatchCacheSize: Long = OddsFeedConfiguration.DEFAULT_MAX_MATCH_CACHE_SIZE
    private var maxFixtureCacheSize: Long = OddsFeedConfiguration.DEFAULT_MAX_FIXTURE_CACHE_SIZE
    private var maxCompetitorCacheSize: Long = OddsFeedConfiguration.DEFAULT_MAX_COMPETITOR_CACHE_SIZE
    private var maxPlayerCacheSize: Long = OddsFeedConfiguration.DEFAULT_MAX_PLAYER_CACHE_SIZE

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
        val mqHost =  "mq-test.integration."+ region.host +"oddin.dev"
        val apiHost = "api-mq-test.integration."+ region.host +"oddin.dev"
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

    fun setMaxMatchCacheSize(maxMatchCacheSize: Long) = apply {
        this.maxMatchCacheSize = maxMatchCacheSize
    }

    fun setMaxFixtureCacheSize(maxFixtureCacheSize: Long) = apply {
        this.maxFixtureCacheSize = maxFixtureCacheSize
    }

    fun setMaxCompetitorCacheSize(maxCompetitorCacheSize: Long) = apply {
        this.maxCompetitorCacheSize = maxCompetitorCacheSize
    }

    fun setMaxPlayerCacheSize(maxPlayerCacheSize: Long) = apply {
        this.maxPlayerCacheSize = maxPlayerCacheSize
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
            maxMatchCacheSize = maxMatchCacheSize,
            maxFixtureCacheSize = maxFixtureCacheSize,
            maxCompetitorCacheSize = maxCompetitorCacheSize,
            maxPlayerCacheSize = maxPlayerCacheSize,
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
