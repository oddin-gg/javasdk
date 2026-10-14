package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** A feed thread left behind fails the check, and only a feed thread does. No broker. */
class LeakCheckTest {

    private static final Duration WAIT = Duration.ofMillis(300);

    @Test
    void aLeakedFeedThreadFailsUntilItEnds() throws Exception {
        var check = LeakCheck.begin();
        var release = new CountDownLatch(1);
        var leaked = parked("oddsfeed-x", release);
        try {
            assertThat(check.leakedThreads()).containsExactly("oddsfeed-x");
            assertThatThrownBy(() -> check.assertClean(WAIT))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("oddsfeed-x");
        } finally {
            release.countDown();
            leaked.join();
        }
        check.assertClean(Duration.ofSeconds(10));
    }

    @Test
    void anAmqpThreadCountsToo() throws Exception {
        var check = LeakCheck.begin();
        var release = new CountDownLatch(1);
        var leaked = parked("AMQP Connection 127.0.0.1:5671", release);
        try {
            assertThat(check.leakedThreads()).hasSize(1);
        } finally {
            release.countDown();
            leaked.join();
        }
    }

    @Test
    void threadsOfOtherNamesAndThreadsFromBeforeAreNotLeaks() throws Exception {
        var release = new CountDownLatch(1);
        var early = parked("oddsfeed-early", release);
        var check = LeakCheck.begin();
        var other = parked("some-other-thread", release);
        try {
            check.assertClean(WAIT);
        } finally {
            release.countDown();
            early.join();
            other.join();
        }
    }

    private static Thread parked(String name, CountDownLatch release) {
        var thread = new Thread(
                () -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
