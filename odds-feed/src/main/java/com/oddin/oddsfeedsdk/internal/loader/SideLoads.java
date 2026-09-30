package com.oddin.oddsfeedsdk.internal.loader;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads worth doing in the background, such as a match's competitors after the match: a bounded
 * queue and a fixed number of workers. Offering never blocks the one who offers; when the queue is
 * full the load is dropped and counted, and the next read fetches it if it is still needed.
 *
 * <p>Safe for concurrent use.
 */
public final class SideLoads implements AutoCloseable {

    private final BlockingQueue<Runnable> queue;
    private final ExecutorService workers;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public SideLoads(int capacity, int workers) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("oddsfeed-side-load-", 0).factory());
        for (int i = 0; i < workers; i++) {
            this.workers.execute(this::work);
        }
    }

    /** Queues the load, or drops and counts it when the queue is full; never waits. */
    public boolean offer(Runnable load) {
        if (queue.offer(load)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    /** Loads dropped because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Loads that threw; a side-load's failure reaches no caller. */
    public long failed() {
        return failed.get();
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            Runnable load;
            try {
                load = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                load.run();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
            }
        }
    }
}
