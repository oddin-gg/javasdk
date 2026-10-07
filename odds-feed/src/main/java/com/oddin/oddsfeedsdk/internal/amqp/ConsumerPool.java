package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.internal.BusySince;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The broker client's consumer threads, as {@code Executors.newFixedThreadPool} makes them, with what
 * the watchdog reads of them: since when the longest running task has run, how many wait, and how
 * many have run. The broker client hands each channel's deliveries over in tasks of a few, one task
 * per channel at a time, so a task that runs for long is a hand-off that does not return.
 */
final class ConsumerPool extends ThreadPoolExecutor {

    /** {@link System#nanoTime}, or a test's. */
    private final LongSupplier nanos;
    /** When each thread running a task began it, a {@link BusySince} by {@link #nanos}. */
    private final Map<Thread, Long> running = new ConcurrentHashMap<>();

    private final AtomicLong taken = new AtomicLong();

    ConsumerPool(int threads, ThreadFactory factory, LongSupplier nanos) {
        super(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
        this.nanos = nanos;
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable task) {
        running.put(thread, BusySince.mark(nanos.getAsLong()));
        super.beforeExecute(thread, task);
    }

    @Override
    protected void afterExecute(Runnable task, @Nullable Throwable failure) {
        super.afterExecute(task, failure);
        running.remove(Thread.currentThread());
        taken.incrementAndGet();
    }

    /** When the task running longest began, a {@link BusySince}; {@link BusySince#IDLE} when none runs. */
    long busySince() {
        long oldest = BusySince.IDLE;
        for (long since : running.values()) {
            // by their difference: a reading of System.nanoTime may be negative
            if (oldest == BusySince.IDLE || since - oldest < 0) {
                oldest = since;
            }
        }
        return oldest;
    }

    /** Tasks waiting for a thread. */
    int waiting() {
        return getQueue().size();
    }

    /** Tasks run to their end. */
    long taken() {
        return taken.get();
    }
}
