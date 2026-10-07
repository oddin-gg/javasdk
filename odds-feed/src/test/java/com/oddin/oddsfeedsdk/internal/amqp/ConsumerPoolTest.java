package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the watchdog reads of the consumer threads: the hand-off running longest, what waits, what ran. */
class ConsumerPoolTest {

    private static final long WAIT_SECONDS = 10;

    private volatile Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final InstantSource clock = () -> now;
    private final ConsumerPool pool = new ConsumerPool(
            2, Thread.ofPlatform().daemon().name("test-consumer-", 0).factory(), clock);

    @AfterEach
    void close() {
        pool.shutdownNow();
    }

    @Test
    void theHandOffRunningLongestIsTheOneSeenAndWhatWaitsIsCounted() throws InterruptedException {
        assertThat(pool.busySince()).isZero();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        long first = now.toEpochMilli();
        pool.execute(() -> hold(entered, release));
        awaitBusy();
        now = now.plusSeconds(5);
        pool.execute(() -> hold(entered, release));
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});
        assertThat(pool.busySince()).as("the longer of the two running").isEqualTo(first);
        assertThat(pool.waiting()).as("the third, behind both threads").isEqualTo(1);
        assertThat(pool.taken()).isZero();

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (pool.taken() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.taken()).isEqualTo(3);
        assertThat(pool.busySince()).isZero();
        assertThat(pool.waiting()).isZero();
    }

    private void awaitBusy() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (pool.busySince() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static void hold(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
