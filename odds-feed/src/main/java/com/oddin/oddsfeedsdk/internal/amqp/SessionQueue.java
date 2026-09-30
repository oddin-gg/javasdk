package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A session's deliveries between the broker and the session's dispatcher. It holds as many as the
 * prefetch lets the broker send unacknowledged; before a new channel's consumer starts, the old
 * channel's deliveries are taken out, and a late one from the old channel is turned away, under the
 * same lock - so the new channel's room is its own and adding never waits. Should it be full all the
 * same, the delivery is counted and refused, never waited for: the consumer thread serves every
 * session.
 *
 * <p>Safe for concurrent use: the broker's consumer thread adds, the session's dispatcher takes.
 */
public final class SessionQueue {

    private final BlockingQueue<RawDelivery> deliveries;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong overflowed = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private long oldestEpoch;

    public SessionQueue(int capacity) {
        this.deliveries = new ArrayBlockingQueue<>(capacity);
    }

    /** Adds without waiting, unless it is full or the delivery comes from a replaced channel. */
    Offer offer(RawDelivery delivery) {
        lock.lock();
        try {
            if (delivery.epoch() < oldestEpoch) {
                stale.incrementAndGet();
                return Offer.STALE;
            }
            if (deliveries.offer(delivery)) {
                return Offer.QUEUED;
            }
            overflowed.incrementAndGet();
            return Offer.FULL;
        } finally {
            lock.unlock();
        }
    }

    /** What became of a delivery offered. */
    enum Offer {
        QUEUED,
        /** refused for want of room: its channel is live, and it is acknowledged there */
        FULL,
        /** from a replaced channel, which is gone and took the delivery with it */
        STALE
    }

    /** The next delivery, waiting at most {@code timeout}, or null. */
    public @Nullable RawDelivery poll(Duration timeout) throws InterruptedException {
        return deliveries.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * From now on only deliveries of {@code epoch} or later: those of older channels are taken out,
     * and turned away when they come late. What a channel replacement does first.
     */
    void removeEpochsBefore(long epoch) {
        lock.lock();
        try {
            oldestEpoch = Math.max(oldestEpoch, epoch);
            deliveries.removeIf(delivery -> delivery.epoch() < epoch);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        return deliveries.size();
    }

    /** Deliveries refused because the queue was full, which the prefetch should never let happen. */
    public long overflowed() {
        return overflowed.get();
    }

    /** Deliveries of a replaced channel that arrived after it was replaced. */
    public long stale() {
        return stale.get();
    }
}
