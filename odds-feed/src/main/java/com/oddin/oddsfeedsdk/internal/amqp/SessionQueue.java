package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A session's deliveries between the broker and the session's dispatcher. It holds as many as the
 * prefetch lets the broker send unacknowledged, and before a new channel's consumer starts, the
 * old channel's deliveries are taken out; so adding never waits. Should it ever be full all the
 * same, the delivery is counted and dropped, never waited for: the consumer thread serves every
 * session.
 *
 * <p>Safe for concurrent use: the broker's consumer thread adds, the session's dispatcher takes.
 */
public final class SessionQueue {

    private final BlockingQueue<RawDelivery> deliveries;
    private final AtomicLong overflowed = new AtomicLong();

    public SessionQueue(int prefetch) {
        this.deliveries = new ArrayBlockingQueue<>(prefetch);
    }

    /** Adds without waiting; false, and counted, when full. */
    boolean offer(RawDelivery delivery) {
        if (deliveries.offer(delivery)) {
            return true;
        }
        overflowed.incrementAndGet();
        return false;
    }

    /** The next delivery, waiting at most {@code timeout}, or null. */
    public @Nullable RawDelivery poll(Duration timeout) throws InterruptedException {
        return deliveries.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Takes out every delivery of a channel before {@code epoch}: what a channel replacement does first. */
    void removeEpochsBefore(long epoch) {
        deliveries.removeIf(delivery -> delivery.epoch() < epoch);
    }

    public int size() {
        return deliveries.size();
    }

    /** Deliveries dropped because the queue was full, which the prefetch should never let happen. */
    public long overflowed() {
        return overflowed.get();
    }
}
