package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A stalled sample or event fails the sentinel; healthy ones do not, and an unused one fails too. */
class HealthSentinelTest {

    @Test
    void healthySamplesAndTransitionsPass() {
        var sentinel = new HealthSentinel(() -> health(HealthState.HEALTHY));
        sentinel.sample();
        sentinel.onHealthEvent(event(HealthState.HEALTHY, HealthState.DEGRADED));

        sentinel.assertNeverStalled();
        assertThat(sentinel.events()).hasSize(1);
    }

    @Test
    void aStalledSampleFails() {
        var current = new AtomicReference<>(HealthState.HEALTHY);
        var sentinel = new HealthSentinel(() -> health(current.get()));
        sentinel.sample();
        current.set(HealthState.STALLED);
        sentinel.sample();

        assertThatThrownBy(sentinel::assertNeverStalled)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("STALLED");
    }

    @Test
    void aStalledEventFails() {
        var sentinel = new HealthSentinel(() -> health(HealthState.HEALTHY));
        sentinel.sample();
        sentinel.onHealthEvent(event(HealthState.DEGRADED, HealthState.STALLED));

        assertThatThrownBy(sentinel::assertNeverStalled).isInstanceOf(AssertionError.class);
    }

    @Test
    void aSentinelThatNeverSampledFailsRatherThanPassingVacuously() {
        var sentinel = new HealthSentinel(() -> health(HealthState.HEALTHY));

        assertThatThrownBy(sentinel::assertNeverStalled).isInstanceOf(AssertionError.class);
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeWaitsForASampleInFlightSoItsResultCanBeRead() throws Exception {
        var inSource = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sentinel = new HealthSentinel(() -> {
            inSource.countDown();
            awaitUninterruptibly(release);
            return health(HealthState.STALLED);
        });
        sentinel.start();
        var closer = new Thread(sentinel::close, "closer");
        try {
            assertThat(inSource.await(30, TimeUnit.SECONDS)).isTrue();
            closer.start();
            // the sample cannot finish before the release: close() has to be waiting for it
            while (closer.getState() == Thread.State.NEW || closer.getState() == Thread.State.RUNNABLE) {
                Thread.onSpinWait();
            }
            assertThat(closer.isAlive())
                    .as("close() returned with a sample in flight")
                    .isTrue();
        } finally {
            release.countDown();
            closer.join(30_000);
        }

        assertThat(closer.isAlive()).isFalse();
        assertThatThrownBy(sentinel::assertNeverStalled)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("STALLED");
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void closeFailsLoudlyWhenTheSamplerDoesNotStop() throws Exception {
        var inSource = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sentinel = new HealthSentinel(
                () -> {
                    inSource.countDown();
                    awaitUninterruptibly(release);
                    return health(HealthState.HEALTHY);
                },
                Duration.ofMillis(200));
        sentinel.start();
        try {
            assertThat(inSource.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(sentinel::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not stop");
        } finally {
            release.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static HealthEvent event(HealthState previous, HealthState state) {
        return new HealthEvent(HealthComponent.THREADS, null, previous, state, "test", Instant.now());
    }

    private static FeedHealth health(HealthState state) {
        return new FeedHealth(
                state,
                Map.of(),
                new FeedHealth.Transport(true, 0, 0),
                new FeedHealth.Alives(0, 0, 0, 0),
                List.of(),
                new FeedHealth.Recovery(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                new FeedHealth.Caches(0, 0, 0, 0, 0, 0),
                new FeedHealth.Events(0, 0, 0, 0),
                Instant.now());
    }
}
