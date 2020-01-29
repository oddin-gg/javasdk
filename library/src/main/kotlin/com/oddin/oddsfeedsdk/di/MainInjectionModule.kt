package com.oddin.oddsfeedsdk.di

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import com.oddin.oddsfeedsdk.*
import com.oddin.oddsfeedsdk.api.*
import com.oddin.oddsfeedsdk.api.factories.*
import com.oddin.oddsfeedsdk.cache.*
import com.oddin.oddsfeedsdk.cache.market.*
import com.oddin.oddsfeedsdk.cache.entity.*
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.mq.*
import com.oddin.oddsfeedsdk.mq.rabbit.AMQPConnectionProvider
import com.oddin.oddsfeedsdk.mq.rabbit.SingleAMQPConnectionProvider
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener
import com.rabbitmq.client.ConnectionFactory


class MainInjectionModule(
    private val configuration: OddsFeedConfiguration,
    private val globalEventsListener: GlobalEventsListener
) :
    AbstractModule() {

    override fun configure() {
        binder().disableCircularProxies()
        bind(GlobalEventsListener::class.java).toInstance(globalEventsListener)
        bind(OddsFeedConfiguration::class.java).toInstance(configuration)
        bind(ConnectionFactory::class.java).`in`(Singleton::class.java)
        bind(AMQPConnectionProvider::class.java).to(SingleAMQPConnectionProvider::class.java)
            .`in`(Singleton::class.java)
        bind(WhoAmIManager::class.java).to(WhoAmIManagerImpl::class.java).`in`(Singleton::class.java)
        bind(OddsFeedSessionImpl::class.java)
        bind(ChannelConsumer::class.java).to(ChannelConsumerImpl::class.java)
        bind(DispatchManager::class.java).to(DispatchManagerImpl::class.java)
        bind(CacheManager::class.java).to(CacheManagerImpl::class.java).`in`(Singleton::class.java)
        bind(SDKProducerManager::class.java).to(ProducerManagerImpl::class.java).`in`(Singleton::class.java)
        bind(FeedMessageFactory::class.java).to(FeedMessageFactoryImpl::class.java)
        bind(RecoveryManager::class.java).to(RecoveryManagerImpl::class.java).`in`(Singleton::class.java)
        bind(MarketDescriptionFactory::class.java).to(
            MarketDescriptionFactoryImpl::class.java
        )
        bind(EntityFactory::class.java).to(
            EntityFactoryImpl::class.java
        ).`in`(Singleton::class.java)
        bind(MarketFactory::class.java).to(MarketFactoryImpl::class.java)
        bind(MarketDataFactory::class.java).to(MarketDataFactoryImpl::class.java)
        bind(ApiClient::class.java).to(ApiClientImpl::class.java).`in`(Singleton::class.java)
        bind(TaskManager::class.java).to(TaskManagerImpl::class.java).`in`(Singleton::class.java)
        bind(SportsInfoManager::class.java).to(SportsInfoManagerImpl::class.java).`in`(Singleton::class.java)
        bind(MarketDescriptionManager::class.java).to(MarketDescriptionManagerImpl::class.java)
            .`in`(Singleton::class.java)
        bind(CompetitorCache::class.java).to(CompetitorCacheImpl::class.java)
            .`in`(Singleton::class.java)
        bind(SportDataCache::class.java).to(
            SportDataCacheImpl::class.java
        ).`in`(Singleton::class.java)
        bind(TournamentCache::class.java).to(
            TournamentCacheImpl::class.java
        ).`in`(Singleton::class.java)
        bind(MatchCache::class.java).to(
            MatchCacheImpl::class.java
        ).`in`(Singleton::class.java)
        bind(FixtureCache::class.java).to(
            FixtureCacheImpl::class.java
        ).`in`(Singleton::class.java)
        bind(MarketDescriptionCache::class.java).to(MarketDescriptionCacheImpl::class.java)
            .`in`(Singleton::class.java)
        bind(ReplayManager::class.java).to(ReplayManagerImpl::class.java).`in`(Singleton::class.java)
    }

    @Provides
    @Singleton
    @Named("MatchStatusCache")
    fun provideMatchStatusCache(
        config: OddsFeedConfiguration,
        apiClient: ApiClient,
        taskManager: TaskManager
    ): LocalizedStaticDataCache {
        return LocalizedStaticDataCacheImpl(config, apiClient::fetchMatchStatusDescriptions, taskManager)
    }
}