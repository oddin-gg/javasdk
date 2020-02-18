package com.oddin.oddsfeedsdk

import com.google.inject.Guice
import com.google.inject.Injector
import com.oddin.oddsfeedsdk.api.*
import com.oddin.oddsfeedsdk.cache.CacheManager
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder
import com.oddin.oddsfeedsdk.di.MainInjectionModule
import com.oddin.oddsfeedsdk.exceptions.InitException
import com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.rabbit.AMQPConnectionProvider
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import sun.plugin.dom.exception.InvalidStateException

private val logger = KotlinLogging.logger {}

class OddsFeed {
    companion object {
        private const val SNAPSHOT_COMPLETE_ROUTING_KEY_TEMPLATE = "-.-.-.snapshot_complete.-.-.-.%s"

        @JvmStatic
        fun getOddsFeedConfigurationBuilder(): OddsFeedConfigurationBuilder {
            return OddsFeedConfigurationBuilder()
        }
    }

    constructor(
        listener: GlobalEventsListener,
        oddsFeedConfiguration: OddsFeedConfiguration
    ) {
        this@OddsFeed.oddsFeedConfiguration = oddsFeedConfiguration
        this@OddsFeed.injector =
            Guice.createInjector(arrayListOf(MainInjectionModule(oddsFeedConfiguration, listener)))
    }

    constructor(
        listener: GlobalEventsListener,
        oddsFeedConfiguration: OddsFeedConfiguration,
        extendedListener: OddsFeedExtListener
    ) : this(listener, oddsFeedConfiguration) {
        oddsFeedExtListener = extendedListener
    }

    private val oddsFeedConfiguration: OddsFeedConfiguration
    private val injector: Injector

    private var possibleAliveSession: SDKOddsFeedSession? = null

    private var feedInitialized = false
    private var feedOpened = false

    private val createdSessionData = mutableSetOf<SessionData>()

    private var _producerManager: SDKProducerManager? = null
    private var _sportsInfoManager: SportsInfoManager? = null
    private var _marketDescriptionManager: MarketDescriptionManager? = null
    private var _bookmakerDetail: BookmakerDetail? = null
    private var _replayManager: ReplayManager? = null

    private var oddsFeedExtListener: OddsFeedExtListener? = null


    fun getSessionBuilder(): OddsFeedSessionBuilder {
        init()
        return OddsFeedSessionBuilderImpl(createdSessionData, injector)
    }

    fun getMarketDescriptionManager(): MarketDescriptionManager {
        init()
        return _marketDescriptionManager ?: throw IllegalStateException("Missing market description manager")
    }

    fun getSportsInfoManager(): SportsInfoManager {
        init()
        return _sportsInfoManager ?: throw IllegalStateException("Missing sports info manager")
    }

    fun getProducerManager(): ProducerManager {
        init()
        return _producerManager ?: throw IllegalStateException("Missing producer manager")
    }

    fun getBookMakerDetail(): BookmakerDetail? {
        init()
        return _bookmakerDetail ?: throw IllegalStateException("Missing book maker detail")
    }

    fun getReplayManager(): ReplayManager? {
        init()
        return _replayManager ?: throw IllegalStateException("Missing replay manager")
    }

    fun open() {
        if (feedOpened) {
            throw InitException("feed cannot already opened", null)
        }

        val producerManager =
            this@OddsFeed._producerManager ?: throw IllegalStateException("failed to init session")
        runBlocking {
            producerManager.open()
        }

        if (createdSessionData.isEmpty()) {
            throw IllegalStateException("Feed created without sessions")
        }

        val availableProducers = producerManager.availableProducers
        val requestedProducers = mutableSetOf<Long>()
        createdSessionData.forEach {
            val producers = it.messageInterest.getPossibleSourceProducers(availableProducers)
            requestedProducers.addAll(producers)
        }

        availableProducers.filter {
            !requestedProducers.contains(it.key)
        }.forEach {
            producerManager.setProducerState(it.key, false)
        }

        val sessionRoutingKeys = generateKeys(
            createdSessionData.map {
                it.id to (it.messageInterest to it.eventIds)
            }.toMap()
            , oddsFeedConfiguration
        )

        val hasReplay = createdSessionData.any { it.oddsFeedSession is ReplaySession }
        val replayOnly = hasReplay && createdSessionData.size == 1

        val hasAliveMessageInterest =
            createdSessionData.firstOrNull { it.messageInterest == MessageInterest.SYSTEM_ALIVE_ONLY } != null
        if (!hasAliveMessageInterest && !replayOnly) {
            possibleAliveSession = injector.getInstance(OddsFeedSessionImpl::class.java)
            possibleAliveSession?.open(
                MessageInterest.SYSTEM_ALIVE_ONLY.routingKeys,
                MessageInterest.SYSTEM_ALIVE_ONLY,
                null,
                null
            )
        }

        createdSessionData.forEach {
            try {
                it.oddsFeedSession.open(
                    sessionRoutingKeys[it.id] ?: throw InvalidStateException("missing routing keys for session"),
                    it.messageInterest,
                    it.oddsFeedListener,
                    oddsFeedExtListener
                )
            } catch (ex: Exception) {
                throw InitException("Failed to init feed", ex)
            }
        }

        injector.getInstance(RecoveryManager::class.java).open(replayOnly)
        injector.getInstance(TaskManager::class.java).open()
        injector.getInstance(ApiClient::class.java).subscribeForData(oddsFeedExtListener)

        feedOpened = true
    }

    fun close() {
        try {
            createdSessionData.forEach { it.oddsFeedSession.close() }
            possibleAliveSession?.close()
            injector.getInstance(AMQPConnectionProvider::class.java).close()
            injector.getInstance(TaskManager::class.java).close()
            injector.getInstance(CacheManager::class.java).close()
            injector.getInstance(ApiClient::class.java).close()
        } catch (e: Exception) {
            logger.error { "Failed to close with $e" }
        }

        logger.debug { "Odds feed closed " }
    }

    private fun init() {
        if (feedInitialized) {
            return
        }

        val whoAmIProvider = this@OddsFeed.injector.getInstance(WhoAmIManager::class.java)

        runBlocking {
            try {
                whoAmIProvider.fetchBookmakerDetails()
            } catch (e: Exception) {
                throw InitException("Failed to init odds feed", e)
            }
        }

        this@OddsFeed._bookmakerDetail = whoAmIProvider.bookmakerDetail
        this@OddsFeed._producerManager = injector.getInstance(SDKProducerManager::class.java)
        this@OddsFeed._marketDescriptionManager = injector.getInstance(MarketDescriptionManager::class.java)
        this@OddsFeed._sportsInfoManager = injector.getInstance(SportsInfoManager::class.java)
        this@OddsFeed._replayManager = injector.getInstance(ReplayManager::class.java)
        this@OddsFeed.feedInitialized = true
    }

    private fun generateKeys(
        sessionsData: Map<String, Pair<MessageInterest, Set<URN>>>,
        oddsFeedConfiguration: OddsFeedConfiguration
    ): Map<String, List<String>> {
        validateInterestCombination(sessionsData)
        val bothLowAndHigh =
            sessionsData.values.filter { it.first == MessageInterest.LOW_PRIORITY_ONLY || it.first == MessageInterest.HI_PRIORITY_ONLY }
                .size == 2

        val snapshotRoutingKey = String.format(
            SNAPSHOT_COMPLETE_ROUTING_KEY_TEMPLATE,
            oddsFeedConfiguration.sdkNodeId ?: "-"
        )

        return sessionsData.map {
            val sessionRoutingKeys = mutableSetOf<String>()
            val basicRoutingKeys = when (it.value.first) {
                MessageInterest.SPECIFIED_MATCHES_ONLY -> {
                    it.value.second.map { urn -> "#.${urn.prefix}:${urn.type}.${urn.id}" }
                }
                else -> it.value.first.routingKeys
            }

            basicRoutingKeys.forEach { key ->
                val basicRoutingKey = if (oddsFeedConfiguration.sdkNodeId != null) {
                    sessionRoutingKeys.add("${key}.${oddsFeedConfiguration.sdkNodeId}.#")
                    "$key.-.#"
                } else {
                    "$key.#"
                }

                if (bothLowAndHigh && it.value.first == MessageInterest.LOW_PRIORITY_ONLY) {
                    sessionRoutingKeys.add(basicRoutingKey)
                } else {
                    sessionRoutingKeys.add(snapshotRoutingKey)
                    sessionRoutingKeys.add(basicRoutingKey)
                }
            }

            if (it.value.first != MessageInterest.SYSTEM_ALIVE_ONLY) {
                sessionRoutingKeys.add(MessageInterest.SYSTEM_ALIVE_ONLY.routingKeys.first())
            }

            it.key to sessionRoutingKeys.distinct()
        }.toMap()
    }

    private fun validateInterestCombination(sessionsData: Map<String, Pair<MessageInterest, Set<URN>>>) {
        check(sessionsData.isNotEmpty())
        if (sessionsData.count() == 1) {
            return
        }

        val userInterests = sessionsData.values.map { it.first }.toSet()
        val allInterest = sessionsData.values
        if (userInterests.size != allInterest.size) {
            throw UnsupportedMessageInterestCombination("found duplicate message interests", null)
        }

        val hasAll = sessionsData.values.any { it.first == MessageInterest.ALL }
        if (hasAll) {
            throw UnsupportedMessageInterestCombination(
                "all messages can be used only for single session configuration",
                null
            )
        }

        val hasPriority =
            sessionsData.values.any { it.first == MessageInterest.HI_PRIORITY_ONLY || it.first == MessageInterest.LOW_PRIORITY_ONLY }
        val hasMessages =
            sessionsData.values.any { it.first == MessageInterest.PREMATCH_ONLY || it.first == MessageInterest.LIVE_ONLY }
        if (hasPriority && hasMessages) {
            throw UnsupportedMessageInterestCombination("cannot combine priority messages with other types", null)
        }
    }

}