package com.oddin.oddsfeedsdk.internal.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Background loads never hold up the one who offers them. */
class SideLoadsTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);

    @Test
    void eachLoadHasTheWholeDeadlineFromWhenItStarts() throws Exception {
        var remaining = new java.util.concurrent.CompletableFuture<Duration>();
        try (var sideLoads = new SideLoads(10, 1, BUDGET)) {
            sideLoads.offer(_ -> sleep(Duration.ofMillis(500)));
            sideLoads.offer(deadline -> remaining.complete(deadline.remaining()));
            assertThat(remaining.get(5, TimeUnit.SECONDS))
                    .as("not counted from the offer, half a second earlier")
                    .isGreaterThan(BUDGET.minusMillis(300))
                    .isLessThanOrEqualTo(BUDGET);
        }
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfWaiting() throws InterruptedException {
        var busy = new CountDownLatch(1);
        var working = new CountDownLatch(2);
        var done = new AtomicInteger();
        try (var sideLoads = new SideLoads(3, 2, BUDGET)) {
            for (int i = 0; i < 2; i++) {
                sideLoads.offer(_ -> {
                    working.countDown();
                    await(busy);
                    done.incrementAndGet();
                });
            }
            assertThat(working.await(5, TimeUnit.SECONDS))
                    .as("both workers busy")
                    .isTrue();

            long started = System.nanoTime();
            int accepted = 0;
            for (int i = 0; i < 10; i++) {
                if (sideLoads.offer(_ -> done.incrementAndGet())) {
                    accepted++;
                }
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
            assertThat(accepted).as("the queue's capacity").isEqualTo(3);
            assertThat(sideLoads.dropped()).isEqualTo(7);

            busy.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (done.get() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(done).as("the two, then the three queued").hasValue(5);
        }
    }

    @Test
    void aLoadThatThrowsIsCountedAndTheWorkerCarriesOn() throws InterruptedException {
        var ran = new CountDownLatch(1);
        try (var sideLoads = new SideLoads(10, 1, BUDGET)) {
            sideLoads.offer(_ -> {
                throw new IllegalStateException("the API said no");
            });
            sideLoads.offer(_ -> {
                throw new ExceptionInInitializerError("a binding did not load");
            });
            sideLoads.offer(_ -> ran.countDown());
            assertThat(ran.await(5, TimeUnit.SECONDS))
                    .as("the one worker is still there")
                    .isTrue();
            assertThat(sideLoads.failed()).isEqualTo(2);
        }
    }

    @Test
    void aLoadThatSwallowsTheCloseDoesNotKeepItsWorker() throws InterruptedException {
        var running = new CountDownLatch(1);
        var sideLoads = new SideLoads(10, 1, BUDGET);
        sideLoads.offer(_ -> {
            running.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException swallowed) {
                // what a careless load does: the interrupt is gone, and it fails some other way
                throw new IllegalStateException("interrupted");
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        sideLoads.close();
        assertThat(sideLoads.awaitClosed(Duration.ofSeconds(5)))
                .as("the worker ended")
                .isTrue();
        assertThat(sideLoads.failed()).isEqualTo(1);
        assertThat(sideLoads.offer(_ -> {}))
                .as("nothing is taken after the close")
                .isFalse();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
