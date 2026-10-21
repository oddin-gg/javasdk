package com.oddin.oddsfeedsdk

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.oddin.oddsfeedsdk.api.WhoAmIManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ScheduledExecutorService.scheduleAtFixedRate stops all later runs of a task whose run
// throws, and says nothing about it. The recovery timer and the static data refresh run as
// periodic tasks, so one exception must not end them.
class TaskManagerTest {

    private class TaskFailure : Error("task failure")

    private val whoAmIManager = mockk<WhoAmIManager> {
        every { bookmakerDescription() } returns "test"
    }

    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var manager: TaskManagerImpl

    @Before
    fun setUp() {
        appender.start()
        root.addAppender(appender)
        manager = TaskManagerImpl(whoAmIManager)
    }

    @After
    fun tearDown() {
        manager.close()
        root.detachAppender(appender)
    }

    private fun task(name: String, command: () -> Unit, period: Long = 10) =
        PeriodicTask(name, command, 0, TimeUnit.MILLISECONDS, period)

    private fun failureLogged(name: String) = appender.list.filter {
        it.level == Level.ERROR && it.formattedMessage.contains("periodic task $name") && it.throwableProxy != null
    }

    private fun runAgainAfterFailure(failure: () -> Throwable): Boolean {
        val runs = AtomicInteger()
        val ranAgain = CountDownLatch(1)
        manager.startTaskPeriodically(
            task("failing", {
                if (runs.incrementAndGet() == 1) {
                    throw failure()
                }
                ranAgain.countDown()
            })
        )
        return ranAgain.await(10, TimeUnit.SECONDS)
    }

    @Test(timeout = 60_000)
    fun aPeriodicTaskThatThrowsRunsAgain() {
        assertTrue("the task did not run again after it threw", runAgainAfterFailure { IllegalStateException("boom") })
        assertTrue("the failure was not logged with the task's name", failureLogged("failing").isNotEmpty())
    }

    @Test(timeout = 60_000)
    fun aPeriodicTaskThatFailsWithACheckedExceptionRunsAgain() {
        assertTrue("the task did not run again after it threw", runAgainAfterFailure { java.io.IOException("boom") })
    }

    @Test(timeout = 60_000)
    fun anErrorIsLoggedAndPropagatesOutOfTheTask() {
        val failure = TaskFailure()
        val guarded = manager.guarded(task("fatal", { throw failure }))

        try {
            guarded.run()
            fail("the error did not propagate")
        } catch (e: TaskFailure) {
            assertSame(failure, e)
        }

        val logged = failureLogged("fatal")
        assertEquals(1, logged.size)
        assertEquals(TaskFailure::class.java.name, logged[0].throwableProxy.className)
    }

    @Test(timeout = 60_000)
    fun anInterruptedTaskReturnsKeepsTheInterruptAndIsNotLoggedAsAFailure() {
        val guarded = manager.guarded(task("interrupted", { throw InterruptedException("stop") }))

        try {
            // close() interrupts a running task: the run ends quietly, with the flag left for the executor
            guarded.run()

            assertTrue("the interrupt was not kept", Thread.currentThread().isInterrupted)
            assertTrue(
                "an interrupt was logged as a failure: ${appender.list.map { it.formattedMessage }}",
                appender.list.none { it.level == Level.ERROR && it.formattedMessage.contains("interrupted") }
            )
        } finally {
            // the test thread's flag, so that nothing after it is interrupted
            Thread.interrupted()
        }
    }

    @Test(timeout = 60_000)
    fun aTaskInterruptedOnItsOwnThreadLeavesTheInterruptOnThatThread() {
        val inTask = CountDownLatch(1)
        val release = CountDownLatch(1)
        val guarded = manager.guarded(task("blocked", {
            inTask.countDown()
            release.await()
        }))
        val flagAfterRun = AtomicReference<Boolean>()
        val runner = Thread {
            guarded.run()
            flagAfterRun.set(Thread.currentThread().isInterrupted)
        }
        runner.start()
        try {
            assertTrue("the task did not start", inTask.await(10, TimeUnit.SECONDS))

            // what close() does to a running task
            runner.interrupt()
            runner.join(10_000)

            assertFalse("the run did not end", runner.isAlive)
            assertEquals("the interrupt was not left on the thread that ran the task", true, flagAfterRun.get())
            assertTrue(
                "an interrupt was logged as a failure: ${appender.list.map { it.formattedMessage }}",
                failureLogged("blocked").isEmpty()
            )
        } finally {
            release.countDown()
            runner.interrupt()
            runner.join(10_000)
        }
    }

    @Test(timeout = 60_000)
    fun aPeriodicTaskThatWasInterruptedOnceRunsAgain() {
        // the interrupt of one run (a thread of the pool, not close()) is not an end of the task
        val runs = AtomicInteger()
        val ranAgain = CountDownLatch(1)
        manager.startTaskPeriodically(task("interrupted", {
            if (runs.incrementAndGet() == 1) {
                throw InterruptedException("stop")
            }
            ranAgain.countDown()
        }))

        assertTrue("the task did not run again after it was interrupted", ranAgain.await(10, TimeUnit.SECONDS))
        assertTrue(
            "an interrupt was logged as a failure: ${appender.list.map { it.formattedMessage }}",
            failureLogged("interrupted").isEmpty()
        )
    }

    @Test(timeout = 60_000)
    fun aTaskThatFailedWithAnErrorIsNotRunAgain() {
        val failingRuns = AtomicInteger()
        manager.startTaskPeriodically(task("fatal", {
            failingRuns.incrementAndGet()
            throw TaskFailure()
        }))
        // The scheduler runs one task at a time, in the order they fall due. The second task
        // is scheduled after the first with the same period, so every run of the first one
        // that is still scheduled comes before the matching run of the second.
        val observed = CountDownLatch(5)
        manager.startTaskPeriodically(task("observer", { observed.countDown() }))

        assertTrue("the observing task did not run", observed.await(10, TimeUnit.SECONDS))
        assertEquals("the task ran again after an error", 1, failingRuns.get())
        assertEquals(1, failureLogged("fatal").size)
    }
}
