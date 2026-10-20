package com.oddin.oddsfeedsdk

import com.oddin.oddsfeedsdk.api.WhoAmIManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ScheduledExecutorService.scheduleAtFixedRate stops all later runs of a task whose run
// throws, and says nothing about it. The recovery timer and the static data refresh run as
// periodic tasks, so one exception must not end them.
class TaskManagerTest {

    private val whoAmIManager = mockk<WhoAmIManager> {
        every { bookmakerDescription() } returns "test"
    }

    private fun runAgainAfterFailure(failure: () -> Throwable): Boolean {
        val manager = TaskManagerImpl(whoAmIManager)
        val runs = AtomicInteger()
        val ranAgain = CountDownLatch(1)
        try {
            manager.startTaskPeriodically(
                PeriodicTask(
                    "failing",
                    {
                        if (runs.incrementAndGet() == 1) {
                            throw failure()
                        }
                        ranAgain.countDown()
                    },
                    0,
                    TimeUnit.MILLISECONDS,
                    10
                )
            )
            return ranAgain.await(10, TimeUnit.SECONDS)
        } finally {
            manager.close()
        }
    }

    @Test(timeout = 60_000)
    fun aPeriodicTaskThatThrowsRunsAgain() {
        assertTrue("the task did not run again after it threw", runAgainAfterFailure { IllegalStateException("boom") })
    }

    @Test(timeout = 60_000)
    fun aPeriodicTaskThatFailsWithACheckedExceptionRunsAgain() {
        assertTrue("the task did not run again after it threw", runAgainAfterFailure { java.io.IOException("boom") })
    }
}
