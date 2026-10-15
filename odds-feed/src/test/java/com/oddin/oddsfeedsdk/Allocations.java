package com.oddin.oddsfeedsdk;

import java.lang.management.ManagementFactory;

/**
 * What a piece of code allocates, in bytes a call, counted by the JVM for the calling thread. A test calls the
 * code a few hundred times, so that it runs in the interpreter and the first compiler tier, where nothing an
 * optimizer could drop is dropped: the figure is what the code does, not what one run's optimizer made of it.
 * Object sizes depend on the JVM's pointers, so a test compares what it measures with what the code it
 * replaced made, or holds it to a figure with room, and does not pin it to a number of bytes.
 */
public final class Allocations {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Allocations() {}

    /** The fewest bytes one call took over {@code rounds} rounds of {@code calls}, after a warm-up of {@code calls}. */
    public static long perCall(int calls, int rounds, Runnable call) {
        for (int i = 0; i < calls; i++) {
            call.run();
        }
        long fewest = Long.MAX_VALUE;
        for (int round = 0; round < rounds; round++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < calls; i++) {
                call.run();
            }
            long after = THREADS.getCurrentThreadAllocatedBytes();
            fewest = Math.min(fewest, (after - before) / calls);
        }
        return fewest;
    }
}
