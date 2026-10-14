package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The sentinel sees a real deadlock and a worker that stopped, and stays quiet otherwise. */
class DeadlockSentinelTest {

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aDeliberatelyDeadlockedPairIsDetected() throws Exception {
        var first = new ReentrantLock();
        var second = new ReentrantLock();
        var bothHoldOne = new CountDownLatch(2);
        var sentinel = new DeadlockSentinel(Duration.ofSeconds(60)).start();
        var a = deadlocker(first, second, bothHoldOne);
        var b = deadlocker(second, first, bothHoldOne);
        try {
            assertThat(sentinel.awaitProblem(Duration.ofSeconds(30)))
                    .as("a problem within 30 s")
                    .isTrue();
            assertThat(sentinel.problem()).contains("deadlocked threads");
            assertThatThrownBy(sentinel::close)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("deadlocked");
        } finally {
            a.interrupt();
            b.interrupt();
            a.join();
            b.join();
        }
    }

    @Test
    void aSentinelWithNothingWrongCloses() {
        try (var sentinel = new DeadlockSentinel(Duration.ofSeconds(10))) {
            sentinel.worker("idle").done();
            sentinel.poll();
            assertThat(sentinel.problem()).isNull();
        }
    }

    @Test
    void aWorkerThatStopsTickingIsStuckOnceTheLimitPasses() {
        var now = new AtomicLong();
        var sentinel = new DeadlockSentinel(Duration.ofSeconds(10), now::get);
        var worker = sentinel.worker("reader-1");

        now.set(Duration.ofSeconds(9).toNanos());
        sentinel.poll();
        assertThat(sentinel.problem()).as("within the limit").isNull();

        worker.tick();
        now.set(Duration.ofSeconds(18).toNanos());
        sentinel.poll();
        assertThat(sentinel.problem()).as("a tick restarts the limit").isNull();

        now.set(Duration.ofSeconds(20).toNanos());
        sentinel.poll();
        assertThat(sentinel.problem()).contains("reader-1").contains("no progress");
        assertThat(worker.ticks()).isEqualTo(1);
    }

    @Test
    void aWorkerThatIsDoneIsNotStuck() {
        var now = new AtomicLong();
        var sentinel = new DeadlockSentinel(Duration.ofSeconds(10), now::get);
        sentinel.worker("reader-1").done();

        now.set(Duration.ofHours(1).toNanos());
        sentinel.poll();

        assertThat(sentinel.problem()).isNull();
    }

    private static Thread deadlocker(ReentrantLock mine, ReentrantLock theirs, CountDownLatch bothHoldOne) {
        var thread = new Thread(
                () -> {
                    try {
                        mine.lockInterruptibly();
                        try {
                            bothHoldOne.countDown();
                            bothHoldOne.await(30, TimeUnit.SECONDS);
                            theirs.lockInterruptibly();
                            theirs.unlock();
                        } finally {
                            mine.unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                "deliberate-deadlock");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
