package com.oddin.oddsfeedsdk.internal.entity;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** One clock for a test to move: the SDK's and Caffeine's. */
final class FakeTime implements InstantSource, Ticker {

    private volatile Instant now = Instant.parse("2026-08-26T17:00:00Z");
    private final AtomicReference<@Nullable Hook> onNextInstant = new AtomicReference<>();

    @Override
    public Instant instant() {
        Hook hook = onNextInstant.get();
        // only on the thread that set it: a loader or a side-load asking the time must not use it up
        if (hook != null && hook.thread().equals(Thread.currentThread()) && onNextInstant.compareAndSet(hook, null)) {
            hook.action().run();
        }
        return now;
    }

    @Override
    public long read() {
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    /**
     * Runs {@code action} once, the next time the calling thread asks for the time: inside a read it
     * makes, between what the read has looked at and what it decides from it.
     */
    void onNextInstant(Runnable action) {
        onNextInstant.set(new Hook(Thread.currentThread(), action));
    }

    private record Hook(Thread thread, Runnable action) {}
}
