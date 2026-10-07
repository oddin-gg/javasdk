package com.oddin.oddsfeedsdk.internal;

import java.time.Duration;

/** How the feed's close waits for its threads. */
public final class Joins {

    private Joins() {}

    /**
     * Waits for {@code thread} to end until {@code deadline}, by {@link System#nanoTime}, and an
     * interrupt does not cut the wait short: a closer interrupted - by an executor's {@code
     * shutdownNow}, say - still waits, and its interrupt is set again before this returns. What the
     * thread does as it ends - the resume points made final - is then done by the time the close
     * returns, interrupted or not.
     *
     * @return whether the thread has ended
     */
    public static boolean uninterruptibly(Thread thread, long deadline) {
        var interrupted = false;
        try {
            while (true) {
                try {
                    return thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
