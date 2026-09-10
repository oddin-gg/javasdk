package com.oddin.oddsfeedsdk.utils

import mu.KotlinLogging
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Counts events that come in bursts and reports the running total at most once a minute:
 * one line per event would bury the log it is meant to explain (a schedule load can drop
 * thousands of side-load responses in a second, and a client clock a few minutes behind
 * would warn on every feed message forever).
 *
 * [record] never throws. It is called from RxJava's `onBackpressureDrop` callback, which
 * cancels the subscription when the callback fails - the exact failure mode this hotfix
 * removes everywhere else.
 */
internal class ThrottledCounter(private val message: (Long) -> String) {
    private val count = AtomicLong()
    private val nextReportAt = AtomicLong(Long.MIN_VALUE)

    fun record() {
        try {
            val total = count.incrementAndGet()
            val now = System.nanoTime()
            val due = nextReportAt.get()
            // Only the thread that moves the deadline forward reports, so a burst
            // produces one line no matter how many threads are counting.
            if (now - due >= 0 && nextReportAt.compareAndSet(due, now + REPORT_INTERVAL_NANOS)) {
                logger.warn { message(total) }
            }
        } catch (e: Exception) {
            // Reporting a problem must never become a bigger one.
        }
    }

    companion object {
        private val REPORT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1)
    }
}
