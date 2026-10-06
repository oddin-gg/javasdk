package com.oddin.oddsfeedsdk.internal.loader;

import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Loads worth doing in the background, such as a match's competitors after the match: a bounded
 * queue and a fixed number of workers. Offering never blocks the one who offers; when the queue is
 * full the load is dropped and counted, and the next read fetches it if it is still needed. Each
 * load has one deadline, the HTTP client timeout from when a worker starts it, for every REST call
 * it makes.
 *
 * <p>Loads that only warm what a reader may want later, such as the members of a list, go to a
 * queue of their own, as bounded, and run only on what the others leave idle: a worker takes one
 * only when the main queue is empty, and at most half the workers, one at least, run them at once.
 * So however many of them a client's reads start, they never take the main queue's room, and
 * never hold every worker while a load of the main queue waits - unless there is one worker only.
 *
 * <p>Safe for concurrent use.
 */
public final class SideLoads implements AutoCloseable {

    /** One load, with the deadline every REST call it makes is given. */
    @FunctionalInterface
    public interface Load {
        void run(Deadline deadline);
    }

    private final int capacity;
    private final int idleLimit;
    private final Duration deadline;
    private final ExecutorService workers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition queued = lock.newCondition();
    /** The main queue; under the lock. */
    private final ArrayDeque<Load> queue = new ArrayDeque<>();
    /** The loads that run only on idle workers; under the lock. */
    private final ArrayDeque<Load> whenIdle = new ArrayDeque<>();
    /** How many of those are running; under the lock. */
    private int idleRunning;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    /**
     * Set before the workers are interrupted: a load can swallow the interrupt, and a worker must
     * not go back to waiting on the queue after the close.
     */
    private volatile boolean closed;

    /**
     * @param capacity how many loads each of the two queues holds
     * @param deadline each load's deadline, the HTTP client timeout
     */
    public SideLoads(int capacity, int workers, Duration deadline) {
        this.capacity = capacity;
        this.idleLimit = Math.max(1, workers / 2);
        this.deadline = deadline;
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("oddsfeed-side-load-", 0).factory());
        for (int i = 0; i < workers; i++) {
            this.workers.execute(this::work);
        }
    }

    /** Queues the load, or drops and counts it when the queue is full; never waits. */
    public boolean offer(Load load) {
        return enqueue(queue, load);
    }

    /**
     * Queues a load that runs only on a worker the main queue leaves idle, or drops and counts it
     * when its own queue is full; never waits, and never takes the main queue's room.
     */
    public boolean offerWhenIdle(Load load) {
        return enqueue(whenIdle, load);
    }

    /** Loads dropped because their queue was full. */
    public long dropped() {
        return dropped.get();
    }

    /** Loads that threw, errors included; a side-load's failure reaches no caller. */
    public long failed() {
        return failed.get();
    }

    @Override
    public void close() {
        closed = true;
        lock.lock();
        try {
            queued.signalAll();
        } finally {
            lock.unlock();
        }
        workers.shutdownNow();
    }

    /** Waits for the workers to end after a close, for up to {@code limit}; for a test. */
    boolean awaitClosed(Duration limit) throws InterruptedException {
        return workers.awaitTermination(limit.toNanos(), TimeUnit.NANOSECONDS);
    }

    private boolean enqueue(ArrayDeque<Load> to, Load load) {
        lock.lock();
        try {
            if (!closed && to.size() < capacity) {
                to.add(load);
                queued.signalAll();
                return true;
            }
        } finally {
            lock.unlock();
        }
        dropped.incrementAndGet();
        return false;
    }

    private void work() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            Taken taken;
            try {
                taken = take();
            } catch (InterruptedException e) {
                return;
            }
            if (taken == null) {
                return;
            }
            try {
                taken.load().run(Deadline.in(deadline));
            } catch (Throwable e) {
                // whatever one load throws, the worker stays for the next
                failed.incrementAndGet();
            } finally {
                if (taken.idle()) {
                    idleDone();
                }
            }
        }
    }

    /** The next load: the main queue's first, else one of the idle queue's while under its limit. */
    private @Nullable Taken take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (!closed) {
                Load load = queue.poll();
                if (load != null) {
                    return new Taken(load, false);
                }
                if (idleRunning < idleLimit && (load = whenIdle.poll()) != null) {
                    idleRunning++;
                    return new Taken(load, true);
                }
                queued.await();
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private void idleDone() {
        lock.lock();
        try {
            idleRunning--;
            queued.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private record Taken(Load load, boolean idle) {}
}
