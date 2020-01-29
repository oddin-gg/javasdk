package com.oddin.oddsfeedsdk.cache

import com.google.inject.Inject
import com.oddin.oddsfeedsdk.PeriodicTask
import com.oddin.oddsfeedsdk.TaskManager
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.TimeUnit

interface StaticData {
    val id: Long
    val description: String
}

interface LocalizedStaticData : StaticData {
    fun getDescription(locale: Locale): String?
}

class LocalizedStaticDataImpl(
    override val id: Long,
    private val map: Map<Locale, String>
) : LocalizedStaticData {

    override fun getDescription(locale: Locale): String? {
        return map[locale]
    }

    override val description: String
        get() = map.values.first()
}


interface LocalizedStaticDataCache {
    fun get(id: Long): LocalizedStaticData?
    fun get(id: Long, locales: List<Locale>): LocalizedStaticData?
    fun exists(id: Long): Boolean
}

class LocalizedStaticDataCacheImpl @Inject constructor(
    oddsFeedConfiguration: OddsFeedConfiguration,
    private val fetcher: suspend (locale: Locale) -> List<StaticData>,
    taskManager: TaskManager
) : LocalizedStaticDataCache {

    private val locales = listOf(oddsFeedConfiguration.defaultLocale)
    private val cache = mutableMapOf<Long, MutableMap<Locale, String>>()
    private val lock = Any()

    private val fetchedLocales: Set<Locale>
        get() = cache.values.flatMap { it.keys }.toSet()

    init {
        taskManager.startTaskPeriodically(
            PeriodicTask(
                "LocalizedStaticDataRefresh",
                this::onRefresh,
                24,
                TimeUnit.HOURS,
                24
            )
        )
    }

    override fun get(id: Long, locales: List<Locale>): LocalizedStaticData? {
        return synchronized(lock) {
            val missingLocales = locales.filter { !fetchedLocales.contains(it) }

            if (missingLocales.isNotEmpty()) {
                fetchData(missingLocales)
            }
            val item = cache[id] ?: return null
            return@synchronized LocalizedStaticDataImpl(id, item)
        }
    }

    override fun get(id: Long): LocalizedStaticData? {
        return get(id, locales)
    }

    override fun exists(id: Long): Boolean {
        return synchronized(lock) {
            if (fetchedLocales.isEmpty()) {
                fetchData(locales)
            }

            return@synchronized cache[id] != null
        }
    }

    private fun fetchData(locales: List<Locale>): Boolean {
        return runBlocking {
            locales.forEach { locale ->
                val data = try {
                    fetcher(locale)
                } catch (e: Exception) {
                    return@runBlocking false
                }

                data.forEach {
                    val localeCache = cache.computeIfAbsent(it.id) { mutableMapOf() }
                    localeCache[locale] = it.description
                }
            }

            return@runBlocking true
        }
    }

    private fun onRefresh() {
        synchronized(lock) {
            fetchData(fetchedLocales.toList())
        }
    }
}