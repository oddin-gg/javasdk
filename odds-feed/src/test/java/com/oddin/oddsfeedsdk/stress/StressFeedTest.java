package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The helpers the scenarios share: a call with a deadline, and a cleanup that keeps the first failure. */
class StressFeedTest {

    @Test
    void aCallThatNeverReturnsFailsAtItsDeadline() {
        var never = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> StressFeed.timedWithin(Duration.ofMillis(200), "the call", () -> {
                        try {
                            never.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("the call did not return within");
        } finally {
            never.countDown();
        }
    }

    @Test
    void aCallThatReturnsIsTimedAndWhatItThrowsIsThrown() {
        assertThat(StressFeed.timedWithin(Duration.ofSeconds(5), "the call", () -> {}))
                .isLessThan(Duration.ofSeconds(5));
        assertThatThrownBy(() -> StressFeed.timedWithin(Duration.ofSeconds(5), "the call", () -> {
                    throw new IllegalStateException("refused");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("refused");
    }

    @Test
    @Timeout(30)
    void everyStepRunsAndTheFirstFailureIsTheOneThrown() {
        var cleanup = new StressFeed.Cleanup();
        var ran = new boolean[1];
        cleanup.step(() -> {
            throw new IllegalStateException("first");
        });
        cleanup.step(() -> {
            throw new AssertionError("second");
        });
        cleanup.step(() -> ran[0] = true);

        assertThat(ran[0]).as("the step after the failures ran").isTrue();
        assertThatThrownBy(() -> cleanup.finish(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("first")
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    @Test
    void aCleanupAfterAFailedRunAttachesItsProblemsToThatFailure() throws Exception {
        var cleanup = new StressFeed.Cleanup();
        cleanup.step(() -> {
            throw new IllegalStateException("cleanup");
        });
        var primary = new AssertionError("the run");

        cleanup.finish(primary);

        assertThat(primary.getSuppressed()).hasSize(1);
        assertThat(primary.getSuppressed()[0]).hasMessage("cleanup");
    }

    @Test
    void aCleanupWithNoProblemThrowsNothing() throws Exception {
        var cleanup = new StressFeed.Cleanup();
        cleanup.step(() -> {});
        cleanup.finish(null);
    }
}
