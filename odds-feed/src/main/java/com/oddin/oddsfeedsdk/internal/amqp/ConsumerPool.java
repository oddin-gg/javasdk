package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The broker client's consumer threads, as {@code Executors.newFixedThreadPool} makes them, with what
 * the watchdog reads of them: since when the longest running task has run, how many wait, and how
 * many have run. The broker client hands each channel's deliveries over in tasks of a few, one task
 * per channel at a time, so a task that runs for long is a hand-off that does not return.
 */
final class ConsumerPool extends ThreadPoolExecutor {

    private final InstantSource clock;
    /** When each thread running a task began it, epoch millis by {@link #clock}. */
    private final Map<Thread, Long> running = new ConcurrentHashMap<>();

    private final AtomicLong taken = new AtomicLong();

    ConsumerPool(int threads, ThreadFactory factory, InstantSource clock) {
        super(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
        this.clock = clock;
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable task) {
        running.put(thread, Math.max(1, clock.millis()));
        super.beforeExecute(thread, task);
    }

    @Override
    protected void afterExecute(Runnable task, @Nullable Throwable failure) {
        super.afterExecute(task, failure);
        running.remove(Thread.currentThread());
        taken.incrementAndGet();
    }

    /** When the task running longest began, epoch millis by the transport's clock; 0 when none runs. */
    long busySince() {
        long oldest = 0;
        for (long since : running.values()) {
            if (oldest == 0 || since < oldest) {
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
