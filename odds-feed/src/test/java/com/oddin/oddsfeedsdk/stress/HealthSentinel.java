package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Samples the feed's health every 250 ms and keeps what the global listener's {@code onHealthEvent}
 * tells, so a run can say afterwards that the feed was never stalled - not only that it ended healthy.
 * Hand {@link #onHealthEvent} the events from the test's listener.
 */
final class HealthSentinel implements AutoCloseable {

    private static final Duration EVERY = Duration.ofMillis(250);

    private final Supplier<FeedHealth> source;
    private final List<HealthEvent> events = new CopyOnWriteArrayList<>();
    private final List<FeedHealth> stalled = new CopyOnWriteArrayList<>();
    private final AtomicInteger samples = new AtomicInteger();
    private volatile Thread sampler;

    HealthSentinel(Supplier<FeedHealth> source) {
        this.source = source;
    }

    /** Samples on a daemon thread until closed. */
    HealthSentinel start() {
        var thread = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        sample();
                        try {
                            Thread.sleep(EVERY);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "stress-health-sentinel");
        thread.setDaemon(true);
        sampler = thread;
        thread.start();
        return this;
    }

    /** One sample. */
    void sample() {
        var health = source.get();
        samples.incrementAndGet();
        if (health.state() == HealthState.STALLED) {
            stalled.add(health);
        }
    }

    void onHealthEvent(HealthEvent event) {
        events.add(event);
    }

    int samples() {
        return samples.get();
    }

    List<HealthEvent> events() {
        return List.copyOf(events);
    }

    /** Fails if any sample or event said STALLED, with the counters of the first sample. */
    void assertNeverStalled() {
        assertThat(samples.get()).as("health samples taken").isPositive();
        assertThat(stalled)
                .as(
                        "samples that read STALLED; the first: %s",
                        stalled.isEmpty() ? "-" : stalled.getFirst().counters())
                .isEmpty();
        assertThat(events.stream().filter(event -> event.state() == HealthState.STALLED))
                .as("health events that said STALLED")
                .isEmpty();
    }

    @Override
    public void close() {
        var thread = sampler;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
