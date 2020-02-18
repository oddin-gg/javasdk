package com.oddin.oddsfeedsdk

import com.google.common.collect.Sets.newConcurrentHashSet
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.ProducerScope
import com.oddin.oddsfeedsdk.api.entities.RecoveryInfoImpl
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.exceptions.GenericOdsFeedException
import com.oddin.oddsfeedsdk.mq.FeedMessageFactory
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.entities.*
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

interface RecoveryManager {
    fun open(replayOnly: Boolean)

    fun initiateEventOddsMessagesRecovery(producerId: Long, eventId: URN): Long?

    fun initiateEventStatefulMessagesRecovery(producerId: Long, eventId: URN): Long?

    fun requestManualProducerRecovery(producerId: Long, timestamp: Long)
}

interface RecoveryMessageProcessor {
    fun onMessageProcessingStarted(sessionId: UUID, producerId: Long, timestamp: Long)

    fun onMessageProcessingEnded(sessionId: UUID, producerId: Long, generatedTimestamp: Long?)

    fun onAliveReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        isSubscribed: Boolean,
        messageInterest: MessageInterest
    )

    fun onSnapshotCompleteReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        requestId: Long,
        messageInterest: MessageInterest
    )
}

class DummyRecoveryMessageProcessorImpl @Inject constructor() : RecoveryMessageProcessor {
    override fun onMessageProcessingStarted(sessionId: UUID, producerId: Long, timestamp: Long) {
        // no-op
    }

    override fun onMessageProcessingEnded(sessionId: UUID, producerId: Long, generatedTimestamp: Long?) {
        // no-op
    }

    override fun onAliveReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        isSubscribed: Boolean,
        messageInterest: MessageInterest
    ) {
        // no-op
    }

    override fun onSnapshotCompleteReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        requestId: Long,
        messageInterest: MessageInterest
    ) {
        // no-op
    }

}

private val logger = KotlinLogging.logger {}

class RecoveryManagerImpl @Inject constructor(
    private val oddsFeedConfiguration: OddsFeedConfiguration,
    private val producerManager: SDKProducerManager,
    private val taskManager: TaskManager,
    private val globalEventsListener: GlobalEventsListener,
    private val feedMessageFactory: FeedMessageFactory,
    private val apiClient: ApiClient
) : RecoveryManager, RecoveryMessageProcessor {
    private val producerRecoveryData = ConcurrentHashMap<Long, ProducerRecoveryData>()
    private val messageProcessingTimes = ConcurrentHashMap<UUID, Long>()
    private var isOpened = false
    private val lock = Any()
    private val sequence = generateSequence(Random.nextLong(20000)) { it + 1L }

    override fun open(replayOnly: Boolean) {
        if (replayOnly) {
            return
        }

        if (isOpened) {
            logger.warn { "Recovery manager already opened. Skipping" }
            return
        }

        val activeProducers = producerManager.activeProducers
        if (activeProducers.isEmpty()) {
            logger.warn { "No active producers" }
        }

        val mappedData = activeProducers.map { it.key to ProducerRecoveryData(it.key, producerManager) }.toMap()
        producerRecoveryData.putAll(mappedData)

        taskManager.startTaskPeriodically(PeriodicTask("RecoveryManager", this::timerTick, 20, TimeUnit.SECONDS, 10))
        isOpened = true
    }

    override fun onMessageProcessingStarted(sessionId: UUID, producerId: Long, timestamp: Long) {
        synchronized(lock) {
            messageProcessingTimes[sessionId] = timestamp
            findOrMakeProducerRecoveryData(producerId).lastMessageReceivedTimestamp = timestamp
        }
    }

    override fun onMessageProcessingEnded(sessionId: UUID, producerId: Long, generatedTimestamp: Long?) {
        synchronized(lock) {
            if (generatedTimestamp != null) {
                findOrMakeProducerRecoveryData(producerId).lastProcessedMessageGenTimestamp = generatedTimestamp
            }

            when (val start = messageProcessingTimes[sessionId] ?: 0) {
                0L -> logger.warn { "Message processing ended, but was not started" }
                else -> {
                    val processTime = System.currentTimeMillis() - start
                    if (processTime > 1000L) {
                        logger.warn { "Processing message took more than 1s" }
                    }

                    messageProcessingTimes[sessionId] = 0
                }
            }
        }
    }

    override fun onAliveReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        isSubscribed: Boolean,
        messageInterest: MessageInterest
    ) {
        synchronized(lock) {
            val producerRecoveryData = findOrMakeProducerRecoveryData(producerId)

            when {
                producerRecoveryData.isDisabled -> return@synchronized
                messageInterest == MessageInterest.SYSTEM_ALIVE_ONLY -> systemSessionAliveReceived(
                    timestamp,
                    isSubscribed,
                    producerRecoveryData
                )
                else -> producerRecoveryData.lastUserSessionAliveReceivedTimestamp = timestamp.created
            }
        }
    }

    override fun onSnapshotCompleteReceived(
        producerId: Long,
        timestamp: MessageTimestamp,
        requestId: Long,
        messageInterest: MessageInterest
    ) {
        synchronized(lock) {
            val producerRecoveryData = producerRecoveryData[producerId] ?: return@synchronized

            when {
                producerRecoveryData.isDisabled -> {
                    logger.info { "Received snapshot recovery complete for disabled producer $producerId" }
                }
                !producerRecoveryData.isKnownRecovery(requestId) -> {
                    logger.info { "Unknown snapshot recovery complete received for request $requestId and producer $producerId" }
                }
                producerRecoveryData.validateSnapshotComplete(requestId, messageInterest) -> snapshotRecoveryFinished(
                    requestId,
                    producerRecoveryData
                )
                producerRecoveryData.validateEventSnapshotComplete(requestId, messageInterest) -> eventRecoveryFinished(
                    requestId,
                    producerRecoveryData
                )
            }
        }
    }

    override fun initiateEventOddsMessagesRecovery(producerId: Long, eventId: URN): Long? {
        return makeEventRecovery(producerId, eventId, apiClient::postEventOddsRecovery)
    }

    override fun initiateEventStatefulMessagesRecovery(producerId: Long, eventId: URN): Long? {
        return makeEventRecovery(producerId, eventId, apiClient::postEventStatefulRecovery)
    }

    override fun requestManualProducerRecovery(producerId: Long, timestamp: Long) {
        synchronized(lock) {
            val producerRecoveryData = findOrMakeProducerRecoveryData(producerId)
            producerDown(producerRecoveryData, ProducerDownReason.OTHER)
            makeSnapshotRecovery(producerRecoveryData, timestamp)
        }
    }

    private fun snapshotRecoveryFinished(requestId: Long, producerRecoveryData: ProducerRecoveryData) {
        val started = producerRecoveryData.lastRecoveryStartedAt ?: throw GenericOdsFeedException(
            "Inconsistent recovery state",
            null
        )
        val finished = System.currentTimeMillis()
        logger.info { "Recovery finished for request $requestId in ${finished - started} ms" }

        if (producerRecoveryData.recoveryState == RecoveryState.INTERRUPTED) {
            makeSnapshotRecovery(producerRecoveryData, producerRecoveryData.lastValidAliveGenTimestampInRecovery ?: 0L)
            return
        }

        val reason = if (producerRecoveryData.firstRecoveryCompleted) {
            ProducerUpReason.RETURNED_FROM_INACTIVITY
        } else {
            ProducerUpReason.FIRST_RECOVERY_COMPLETED
        }

        if (!producerRecoveryData.firstRecoveryCompleted) {
            producerRecoveryData.firstRecoveryCompleted = true
        }

        producerRecoveryData.setProducerRecoveryState(0, 0, RecoveryState.COMPLETED)
        producerUp(producerRecoveryData, reason)
    }

    private fun eventRecoveryFinished(requestId: Long, producerRecoveryData: ProducerRecoveryData) {
        val eventRecovery = producerRecoveryData.getEventRecovery(requestId)
            ?: throw GenericOdsFeedException("Inconsistent event recovery state", null)
        val started = eventRecovery.recoveryStartedAt
        val finished = System.currentTimeMillis()
        logger.info { "Event ${eventRecovery.eventId} recovery finished for request $requestId in ${finished - started} ms" }

        GlobalScope.launch {
            globalEventsListener.onEventRecoveryCompleted(eventRecovery.eventId, requestId)
        }

        producerRecoveryData.eventRecoveryCompleted(requestId)
    }

    private fun makeEventRecovery(
        producerId: Long,
        eventId: URN,
        callable: suspend (String, URN, Long, Int?) -> Boolean
    ): Long? {
        return synchronized(lock) {
            val now = System.currentTimeMillis()

            val producerRecoveryData = findOrMakeProducerRecoveryData(producerId)
            val producerName = producerRecoveryData.producerName
                ?: throw GenericOdsFeedException("Cannot find producer for $producerId", null)

            val requestId = sequence.take(1).first()
            producerRecoveryData.setEventRecoveryState(eventId, requestId, now)

            val success = runBlocking {
                try {
                    callable(producerName, eventId, requestId, oddsFeedConfiguration.sdkNodeId)
                } catch (e: Exception) {
                    logger.error { "Event recovery failed with $e" }
                    false
                }
            }

            if (success) {
                requestId
            } else {
                // Recovery failed for some reason, remove info
                producerRecoveryData.eventRecoveryCompleted(requestId)
                null
            }
        }
    }

    private fun systemSessionAliveReceived(
        timestamp: MessageTimestamp,
        subscribed: Boolean,
        producerRecoveryData: ProducerRecoveryData
    ) {
        producerRecoveryData.lastMessageReceivedTimestamp = timestamp.received
        if (!subscribed) {
            if (!producerRecoveryData.isFlaggedDown) {
                producerDown(producerRecoveryData, ProducerDownReason.OTHER)
            }

            makeSnapshotRecovery(producerRecoveryData, producerRecoveryData.timestampForRecovery ?: 0L)
            return
        }

        val now = System.currentTimeMillis()
        val isBackFromInactivity = producerRecoveryData.isFlaggedDown &&
                !producerRecoveryData.isPerformingRecovery &&
                producerRecoveryData.producerDownReason == ProducerDownReason.PROCESSING_QUEUE_DELAY_VIOLATION &&
                calculateTiming(producerRecoveryData, now)
        val isInRecovery = producerRecoveryData.recoveryState != RecoveryState.NOT_STARTED &&
                producerRecoveryData.recoveryState != RecoveryState.ERROR &&
                producerRecoveryData.recoveryState != RecoveryState.INTERRUPTED
        when {
            isBackFromInactivity -> producerUp(producerRecoveryData, ProducerUpReason.RETURNED_FROM_INACTIVITY)
            isInRecovery -> {
                if (producerRecoveryData.isFlaggedDown && !producerRecoveryData.isPerformingRecovery && producerRecoveryData.producerDownReason != ProducerDownReason.PROCESSING_QUEUE_DELAY_VIOLATION) {
                    makeSnapshotRecovery(producerRecoveryData, producerRecoveryData.timestampForRecovery ?: 0L)
                }

                val recoveryTiming = now - (producerRecoveryData.lastRecoveryStartedAt ?: 0)
                val maxInterval = oddsFeedConfiguration.maxRecoveryExecutionMinutes * 60 * 1000L
                if (producerRecoveryData.isPerformingRecovery && recoveryTiming > maxInterval) {
                    // @TODO recoveryId 0
                    producerRecoveryData.setProducerRecoveryState(0, 0, RecoveryState.ERROR)
                    makeSnapshotRecovery(producerRecoveryData, producerRecoveryData.timestampForRecovery ?: 0L)
                }
            }
            else -> makeSnapshotRecovery(producerRecoveryData, producerRecoveryData.timestampForRecovery ?: 0L)
        }

        producerRecoveryData.systemAliveReceived(timestamp.received, timestamp.created)
    }

    private fun producerUp(producerRecoveryData: ProducerRecoveryData, reason: ProducerUpReason) {
        if (producerRecoveryData.isDisabled) {
            return
        }

        if (producerRecoveryData.isFlaggedDown) {
            producerRecoveryData.setProducerUp()
        }

        notifyProducerChangedState(producerRecoveryData, reason.toProducerStatusReason())
    }

    private fun makeSnapshotRecovery(producerRecoveryData: ProducerRecoveryData, from: Long) {
        if (!isOpened) {
            return
        }

        val now = System.currentTimeMillis()
        var recoverFrom = from
        if (recoverFrom != 0L) {
            val recoveryTime = now - recoverFrom
            val maxRecoveryTime = (producerRecoveryData.statefulRecoveryWindowInMinutes ?: 0) * 60 * 1000L
            if (recoveryTime > maxRecoveryTime) {
                recoverFrom = now - maxRecoveryTime
            }
        }

        val requestId = sequence.take(1).first()
        val producerName = producerRecoveryData.producerName
            ?: throw GenericOdsFeedException("Cannot find producer for ${producerRecoveryData.producerId}", null)
        producerRecoveryData.setProducerRecoveryState(requestId, now, RecoveryState.STARTED)

        logger.info { "Recovery started for request $requestId" }
        val succeed = runBlocking {
            try {
                apiClient.postRecovery(
                    producerName,
                    requestId,
                    oddsFeedConfiguration.sdkNodeId,
                    if (recoverFrom == 0L) null else recoverFrom
                )
            } catch (e: Exception) {
                logger.error { "Recovery failed with $e" }
                false
            }
        }

        producerManager.setProducerRecoveryInfo(
            requestId,
            RecoveryInfoImpl(recoverFrom, requestId, now, oddsFeedConfiguration.sdkNodeId, succeed)
        )
    }

    private fun findOrMakeProducerRecoveryData(producerId: Long): ProducerRecoveryData {
        return producerRecoveryData.computeIfAbsent(producerId) {
            ProducerRecoveryData(producerId, producerManager)
        }
    }

    private fun timerTick() {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            producerRecoveryData.forEach {
                val producerRecoveryData = it.value
                if (producerRecoveryData.isDisabled) {
                    return@forEach
                }

                val aliveInterval = now - (producerRecoveryData.lastSystemAliveReceivedTimestamp ?: 0L)
                when {
                    aliveInterval > oddsFeedConfiguration.maxInactivitySeconds * 1000 -> producerDown(
                        producerRecoveryData,
                        ProducerDownReason.ALIVE_INTERVAL_VIOLATION
                    )
                    calculateTiming(producerRecoveryData, now) -> producerDown(
                        producerRecoveryData,
                        ProducerDownReason.PROCESSING_QUEUE_DELAY_VIOLATION
                    )
                }
            }
        }
    }

    private fun producerDown(producerRecoveryData: ProducerRecoveryData, downReason: ProducerDownReason) {
        if (producerRecoveryData.isDisabled) {
            return
        }

        if (producerRecoveryData.isFlaggedDown && producerRecoveryData.producerDownReason != downReason) {
            logger.info { "Changing producer ${producerRecoveryData.producerName} down reason from ${producerRecoveryData.producerDownReason} to $downReason" }
            producerRecoveryData.setProducerDown(downReason)
        }

        if (producerRecoveryData.recoveryState == RecoveryState.STARTED && downReason != ProducerDownReason.PROCESSING_QUEUE_DELAY_VIOLATION) {
            producerRecoveryData.interruptProducerRecovery()
        }

        if (!producerRecoveryData.isFlaggedDown) {
            producerRecoveryData.setProducerDown(downReason)
        }

        notifyProducerChangedState(producerRecoveryData, downReason.toProducerStatusReason())
    }

    private fun notifyProducerChangedState(producerRecoveryData: ProducerRecoveryData, reason: ProducerStatusReason) {
        if (producerRecoveryData.producerStatusReason == reason) {
            return
        }

        producerRecoveryData.producerStatusReason = reason
        val now = System.currentTimeMillis()
        val delayed = !calculateTiming(producerRecoveryData, now)
        val message = feedMessageFactory.buildProducerStatus(
            producerRecoveryData.producerId,
            reason,
            producerRecoveryData.isFlaggedDown,
            delayed,
            now
        )

        GlobalScope.launch {
            globalEventsListener.onProducerStatusChange(message)
        }
    }

    private fun calculateTiming(producerRecoveryData: ProducerRecoveryData, timestamp: Long): Boolean {
        val maxInactivity = oddsFeedConfiguration.maxInactivitySeconds * 1000L
        val messageProcessingDelay = timestamp - (producerRecoveryData.lastProcessedMessageGenTimestamp ?: 0L)
        val userAliveDelay = timestamp - (producerRecoveryData.lastUserSessionAliveReceivedTimestamp ?: 0L)

        return messageProcessingDelay < maxInactivity && userAliveDelay < maxInactivity
    }
}

class ProducerRecoveryData(val producerId: Long, private val producerManager: SDKProducerManager) {
    private val eventRecoveries = ConcurrentHashMap<Long, EventRecovery>()
    private var currentRecovery: RecoveryData? = null

    var lastUserSessionAliveReceivedTimestamp: Long? = null
    var lastValidAliveGenTimestampInRecovery: Long? = null
    var recoveryState: RecoveryState? = null
    var lastSystemAliveReceivedTimestamp: Long? = null
    var firstRecoveryCompleted = false
    var producerDownReason: ProducerDownReason? = null
    var producerStatusReason: ProducerStatusReason? = null

    fun eventRecoveryCompleted(recoveryId: Long) {
        eventRecoveries.remove(recoveryId)
    }

    fun systemAliveReceived(receivedTimestamp: Long, aliveGenTimestamp: Long) {
        lastSystemAliveReceivedTimestamp = receivedTimestamp
        if (!isFlaggedDown) {
            producerManager.setLastAliveReceivedGenTimestamp(producerId, aliveGenTimestamp)
        }

        if (recoveryState === RecoveryState.STARTED) {
            lastValidAliveGenTimestampInRecovery = aliveGenTimestamp
        }
    }

    fun validateSnapshotComplete(recoveryId: Long, messageInterest: MessageInterest): Boolean {
        return when {
            !isPerformingRecovery -> false
            currentRecovery?.recoveryId != recoveryId -> false
            !snapshotValidationNeeded(messageInterest) -> true
            else -> {
                val interests = currentRecovery?.snapshotComplete(messageInterest) ?: emptySet()
                validateProducerSnapshotCompletes(interests)
            }
        }
    }

    fun validateEventSnapshotComplete(recoveryId: Long, messageInterest: MessageInterest): Boolean {
        val eventRecovery = eventRecoveries[recoveryId] ?: return false

        return when {
            snapshotValidationNeeded(messageInterest) -> false
            else -> {
                val interests = eventRecovery.snapshotComplete(messageInterest)
                validateProducerSnapshotCompletes(interests)
            }
        }
    }

    fun isKnownRecovery(requestId: Long): Boolean {
        return currentRecovery?.recoveryId == requestId || eventRecoveries.containsKey(requestId)
    }

    val isPerformingRecovery: Boolean
        get() = recoveryState === RecoveryState.STARTED || recoveryState === RecoveryState.INTERRUPTED

    val isDisabled: Boolean
        get() = !producerManager.isProducerEnabled(producerId)

    val isFlaggedDown: Boolean
        get() = producerManager.isProducerDown(producerId)

    fun getEventRecovery(recoveryId: Long): EventRecovery? {
        return eventRecoveries[recoveryId]
    }

    val lastRecoveryStartedAt: Long?
        get() = currentRecovery?.recoveryStartedAt

    val timestampForRecovery: Long?
        get() = producerManager.getProducer(producerId)?.timestampForRecovery

    var lastMessageReceivedTimestamp: Long?
        get() = producerManager.getProducer(producerId)?.lastMessageTimestamp
        set(value) {
            requireNotNull(value)
            producerManager.setProducerLastMessageTimestamp(producerId, value)
        }

    var lastProcessedMessageGenTimestamp: Long?
        get() = producerManager.getProducer(producerId)?.lastProcessedMessageGenTimestamp
        set(value) {
            requireNotNull(value)
            producerManager.setLastProcessedMessageGenTimestamp(producerId, value)
        }

    val producerName: String?
        get() = producerManager.getProducer(producerId)?.name

    val statefulRecoveryWindowInMinutes: Int?
        get() = producerManager.getProducer(producerId)?.statefulRecoveryWindowInMinutes

    fun setProducerRecoveryState(
        recoveryId: Long,
        recoveryStartedAt: Long,
        recoveryState: RecoveryState
    ) {
        this.recoveryState = recoveryState
        currentRecovery = RecoveryData(recoveryId, recoveryStartedAt)
    }

    fun interruptProducerRecovery() {
        recoveryState = RecoveryState.INTERRUPTED
    }

    fun setProducerDown(producerDownReason: ProducerDownReason) {
        producerManager.setProducerDown(producerId, true)
        this.producerDownReason = producerDownReason
        eventRecoveries.clear()
    }

    fun setProducerUp() {
        producerManager.setProducerDown(producerId, false)
        producerDownReason = null
    }

    fun setEventRecoveryState(eventId: URN, recoveryId: Long, recoveryStartedAt: Long) {
        when {
            recoveryId == 0L && recoveryStartedAt == 0L -> eventRecoveries.remove(recoveryId)
            else -> eventRecoveries[recoveryId] =
                EventRecovery(eventId, recoveryId, recoveryStartedAt)
        }
    }

    private fun snapshotValidationNeeded(messageInterest: MessageInterest): Boolean {
        return messageInterest === MessageInterest.LIVE_ONLY || messageInterest === MessageInterest.PREMATCH_ONLY
    }

    private fun validateProducerSnapshotCompletes(receivedSnapshotCompletes: Set<MessageInterest>): Boolean {
        val notFinished = producerManager
            .getProducer(producerId)
            ?.producerScopes
            ?.map {
                when (it) {
                    ProducerScope.LIVE -> receivedSnapshotCompletes.contains(MessageInterest.LIVE_ONLY)
                    ProducerScope.PREMATCH -> receivedSnapshotCompletes.contains(MessageInterest.PREMATCH_ONLY)
                }
            }?.contains(false) ?: true

        return !notFinished
    }
}

open class RecoveryData(
    val recoveryId: Long,
    val recoveryStartedAt: Long
) {
    private val interestsOfSnapshotComplete = newConcurrentHashSet<MessageInterest>()

    fun snapshotComplete(messageInterest: MessageInterest): Set<MessageInterest> {
        interestsOfSnapshotComplete.add(messageInterest)
        return interestsOfSnapshotComplete
    }
}

class EventRecovery(
    val eventId: URN,
    recoveryId: Long,
    recoveryStartedAt: Long
) : RecoveryData(recoveryId, recoveryStartedAt)