package com.oddin.oddsfeedsdk.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Loads side by side: bounded, in order, and all of them or the first failure. */
class FanOutTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void atMostTheLimitRunAtOnceAndTheResultsKeepTheirOrder() {
        var fanOut = new FanOut(threads, 3, Duration.ofSeconds(5));
        var running = new AtomicInteger();
        var most = new AtomicInteger();
        List<Integer> items = IntStream.range(0, 20).boxed().toList();
        List<Integer> doubled = fanOut.each(items, item -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(20);
            running.decrementAndGet();
            return item * 2;
        });
        assertThat(doubled).isEqualTo(items.stream().map(item -> item * 2).toList());
        assertThat(most.get()).isEqualTo(3);
    }

    @Test
    void aSingleItemIsLoadedOnTheCallersThread() {
        var fanOut = new FanOut(threads, 3, Duration.ofSeconds(5));
        Thread caller = Thread.currentThread();
        assertThat(fanOut.each(List.of(1), item -> Thread.currentThread())).containsExactly(caller);
        assertThat(fanOut.each(List.<Integer>of(), item -> item)).isEmpty();
    }

    @Test
    void theFirstFailureFailsTheWholeAtOnceAndStartsNoMore() throws InterruptedException {
        var fanOut = new FanOut(threads, 2, Duration.ofSeconds(5));
        var started = new AtomicInteger();
        var slowOneDone = new CountDownLatch(1);
        long before = System.nanoTime();
        assertThatThrownBy(() -> fanOut.each(List.of(0, 1, 2, 3, 4), item -> {
                    started.incrementAndGet();
                    if (item == 0) {
                        sleep(2_000);
                        slowOneDone.countDown();
                        return item;
                    }
                    throw new ApiException("item " + item + " failed");
                }))
                .isInstanceOf(ApiException.class)
                .hasMessage("item 1 failed");
        assertThat(Duration.ofNanos(System.nanoTime() - before))
                .as("not waiting for the slow one")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(started.get()).as("none after the failure").isEqualTo(2);
        assertThat(slowOneDone.await(5, TimeUnit.SECONDS))
                .as("a load running finishes on its own")
                .isTrue();
    }

    @Test
    void anErrorStaysAnError() {
        var fanOut = new FanOut(threads, 2, Duration.ofSeconds(5));
        assertThatThrownBy(() -> fanOut.each(List.of(1, 2), item -> {
                    throw new AssertionError("broken");
                }))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aLoadThatOutlastsTheLongestLoadFailsTheWhole() {
        var fanOut = new FanOut(threads, 2, Duration.ofMillis(200));
        assertThatThrownBy(() -> fanOut.each(List.of(1, 2), item -> {
                    sleep(2_000);
                    return item;
                }))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not loaded within 200 ms");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
