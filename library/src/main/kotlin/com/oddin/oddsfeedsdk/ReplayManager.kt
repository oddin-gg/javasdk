package com.oddin.oddsfeedsdk

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.SportsInfoManager
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.utils.URN
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

interface ReplayManager {
    fun getReplayList(): List<SportEvent>?

    fun addSportEvent(event: SportEvent): Boolean
    fun addSportEvent(id: URN): Boolean

    fun removeSportEvent(event: SportEvent): Boolean
    fun removeSportEvent(id: URN): Boolean

    fun play(): Boolean
    fun play(speed: Int, maxDelayInMs: Int): Boolean
    fun play(speed: Int, maxDelayInMs: Int, runParallel: Boolean): Boolean
    fun play(
        speed: Int,
        maxDelayInMs: Int,
        producerId: Int,
        rewriteTimestamps: Boolean
    ): Boolean

    fun play(
        speed: Int,
        maxDelayInMs: Int,
        producerId: Int,
        rewriteTimestamps: Boolean,
        runParallel: Boolean
    ): Boolean

    fun stop(): Boolean
    fun clear(): Boolean
}

private val logger = KotlinLogging.logger {}

class ReplayManagerImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val apiClient: ApiClient,
    private val sportsInfoManager: SportsInfoManager
) : ReplayManager {
    override fun getReplayList(): List<SportEvent>? {
        val data = runBlocking {
            try {
                apiClient.fetchReplaySetContent(oddsFeedConfiguration.sdkNodeId)
            } catch (e: Exception) {
                logger.error { "Failed to fetch replay events with error $e" }
                null
            }
        }

        return data?.mapNotNull { sportsInfoManager.getMatch(URN.parse(it.id)) }
    }

    override fun addSportEvent(event: SportEvent): Boolean {
        val id = event.id ?: return false
        return addSportEvent(id)
    }

    override fun addSportEvent(id: URN): Boolean {
        return runBlocking {
            try {
                apiClient.putReplayEvent(id, oddsFeedConfiguration.sdkNodeId)
            } catch (e: Exception) {
                logger.error { "Failed to add event id $id with error $e" }
                false
            }
        }
    }

    override fun removeSportEvent(event: SportEvent): Boolean {
        val id = event.id ?: return false
        return removeSportEvent(id)
    }

    override fun removeSportEvent(id: URN): Boolean {
        return runBlocking {
            try {
                apiClient.deleteReplayEvent(id, oddsFeedConfiguration.sdkNodeId)
            } catch (e: Exception) {
                logger.error { "Failed to add event id $id with error $e" }
                false
            }
        }
    }

    override fun play(): Boolean {
        return playReplay()
    }

    override fun play(speed: Int, maxDelayInMs: Int): Boolean {
        return playReplay(
            speed = speed,
            maxDelay = maxDelayInMs
        )
    }

    override fun play(speed: Int, maxDelayInMs: Int, runParallel: Boolean): Boolean {
        return playReplay(
            speed = speed,
            maxDelay = maxDelayInMs,
            runParallel = runParallel
        )
    }

    override fun play(speed: Int, maxDelayInMs: Int, producerId: Int, rewriteTimestamps: Boolean): Boolean {
        return playReplay(
            speed = speed,
            maxDelay = maxDelayInMs,
            productId = producerId,
            useReplayTimestamp = rewriteTimestamps
        )
    }

    override fun play(
        speed: Int,
        maxDelayInMs: Int,
        producerId: Int,
        rewriteTimestamps: Boolean,
        runParallel: Boolean
    ): Boolean {
        return playReplay(
            speed = speed,
            maxDelay = maxDelayInMs,
            productId = producerId,
            useReplayTimestamp = rewriteTimestamps,
            runParallel = runParallel
        )
    }

    override fun stop(): Boolean {
        return runBlocking {
            try {
                apiClient.postReplayStop(oddsFeedConfiguration.sdkNodeId)
            } catch (e: Exception) {
                logger.error { "Failed to stop replay with $e" }
                false
            }
        }
    }

    override fun clear(): Boolean {
        return runBlocking {
            try {
                apiClient.postReplayClear(oddsFeedConfiguration.sdkNodeId)
            } catch (e: Exception) {
                logger.error { "Failed to clear replay with $e" }
                false
            }
        }
    }

    private fun playReplay(
        speed: Int? = null,
        maxDelay: Int? = null,
        useReplayTimestamp: Boolean? = null,
        productId: Int? = null,
        runParallel: Boolean? = null
    ): Boolean {
        return runBlocking {
            try {
                apiClient.postReplayStart(
                    nodeId = oddsFeedConfiguration.sdkNodeId,
                    speed = speed,
                    maxDelay = maxDelay,
                    useReplayTimestamp = useReplayTimestamp,
                    productId = productId,
                    runParallel = runParallel
                )
            } catch (e: Exception) {
                logger.error { "Failed play replay with errror $e" }
                false
            }
        }
    }
}