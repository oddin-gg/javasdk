package com.oddin.oddsfeedsdk

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PeriodicTask(
    val name: String,
    val command: () -> Unit,
    val initialDelay: Long,
    val timeUnit: TimeUnit,
    val period: Long
) {
    override fun equals(other: Any?): Boolean {
        return if (other is PeriodicTask) {
            return other.name == name
        } else {
            super.equals(other)
        }
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}

interface TaskManager {
    fun open()
    fun close()
    fun startTaskPeriodically(task: PeriodicTask)
}

private val logger = KotlinLogging.logger {}

class TaskManagerImpl @Inject constructor(whoAmIManager: WhoAmIManager) : TaskManager {
    private val scheduler: ScheduledExecutorService
    private val tasks = Collections.synchronizedSet(mutableSetOf<PeriodicTask>())
    private var isOpened = false

    init {
        val namedThreadFactory =
            ThreadFactoryBuilder().setNameFormat("${whoAmIManager.bookmakerDescription()}-t-%d").build()
        scheduler = Executors.newScheduledThreadPool(1, namedThreadFactory)
    }

    override fun open() {
        if (isOpened) {
            return
        }

        tasks.forEach {
            when (it) {
                is PeriodicTask -> startTaskPeriodically(it)
            }
        }
        tasks.clear()
        isOpened = true
    }

    override fun close() {
        isOpened = false
        scheduler.shutdownNow()
    }

    override fun startTaskPeriodically(task: PeriodicTask) {
        logger.info { "starting periodic task ${task.name}" }
        scheduler.scheduleAtFixedRate(task.command, task.initialDelay, task.period, task.timeUnit)
    }

}