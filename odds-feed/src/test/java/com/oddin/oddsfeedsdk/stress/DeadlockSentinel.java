package com.oddin.oddsfeedsdk.stress;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Notices a wedged run, two ways. It polls the JVM's {@code findDeadlockedThreads()}, which sees
 * platform threads only: a lock cycle among <em>virtual</em> threads, which is where the SDK runs its
 * REST fan-out and its reconnects, is invisible to it. So every worker of a run also reports
 * progress, and a worker that has not moved for the limit counts as stuck, which covers what the JVM
 * cannot see - at the price of a limit long enough that a slow call is not mistaken for a wedge.
 *
 * <p>The first problem found is kept; {@link #close} stops the polling and throws it.
 */
final class DeadlockSentinel implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(100);

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final Duration stuckAfter;
    private final LongSupplier nanos;
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final CountDownLatch tripped = new CountDownLatch(1);
    private volatile String problem;
    private volatile Thread poller;

    DeadlockSentinel(Duration stuckAfter) {
        this(stuckAfter, System::nanoTime);
    }

    /** With the clock a test controls. */
    DeadlockSentinel(Duration stuckAfter, LongSupplier nanos) {
        this.stuckAfter = stuckAfter;
        this.nanos = nanos;
    }

    /** A worker that must {@link Worker#tick} at least every {@code stuckAfter}, until it is {@link Worker#done}. */
    Worker worker(String name) {
        var worker = new Worker(name, nanos.getAsLong());
        workers.add(worker);
        return worker;
    }

    /** Polls on a daemon thread every 100 ms until closed. */
    DeadlockSentinel start() {
        var thread = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted() && problem == null) {
                        poll();
                        try {
                            Thread.sleep(POLL);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "stress-deadlock-sentinel");
        thread.setDaemon(true);
        poller = thread;
        thread.start();
        return this;
    }

    /** One check of both kinds. */
    void poll() {
        long[] deadlocked = threads.findDeadlockedThreads();
        if (deadlocked != null) {
            trip("deadlocked threads: " + Arrays.toString(threads.getThreadInfo(deadlocked, true, true)));
            return;
        }
        long now = nanos.getAsLong();
        for (Worker worker : workers) {
            long silent = now - worker.movedAt;
            if (!worker.done && silent > stuckAfter.toNanos()) {
                trip("worker " + worker.name + " made no progress for " + Duration.ofNanos(silent));
            }
        }
    }

    /** What was found first, or null. */
    String problem() {
        return problem;
    }

    /** Whether a problem is found within the wait. */
    boolean awaitProblem(Duration wait) throws InterruptedException {
        return tripped.await(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        var thread = poller;
        if (thread != null) {
            thread.interrupt();
        }
        poll();
        if (problem != null) {
            throw new AssertionError(problem);
        }
    }

    private synchronized void trip(String found) {
        if (problem == null) {
            problem = found;
            tripped.countDown();
        }
    }

    /** One thread's progress. */
    final class Worker {
        private final String name;
        private final AtomicLong ticks = new AtomicLong();
        private volatile long movedAt;
        private volatile boolean done;

        private Worker(String name, long now) {
            this.name = name;
            this.movedAt = now;
        }

        /** Another unit of work finished. */
        void tick() {
            ticks.incrementAndGet();
            movedAt = nanos.getAsLong();
        }

        /** No more is expected of this worker. */
        void done() {
            done = true;
        }

        long ticks() {
            return ticks.get();
        }
    }
}
