package com.oddin.oddsfeedsdk.internal.dispatch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fixture changes the feed's sessions have delivered, so the same one reaches the client once,
 * whichever session it comes in: one map per feed, keyed as 0.0.x keyed it - producer, event id and
 * the change's timestamp - and remembered for an hour. It is bounded; a change dropped for room
 * before its hour is counted, and would be delivered again if it came again.
 *
 * <p>Safe for concurrent use.
 */
public final class FixtureChanges {

    static final Duration AGE = Duration.ofHours(1);
    static final long SIZE = 100_000;

    private final Cache<String, Boolean> delivered;
    private final AtomicLong evicted = new AtomicLong();

    public FixtureChanges() {
        this(SIZE, Ticker.systemTicker());
    }

    /** With the size and the clock a test sets. */
    FixtureChanges(long size, Ticker ticker) {
        this.delivered = Caffeine.newBuilder()
                .maximumSize(size)
                .expireAfterWrite(AGE)
                .ticker(ticker)
                // eviction on the writing thread: nothing to hand over, nothing left pending
                .executor(Runnable::run)
                .<String, Boolean>evictionListener((key, value, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evicted.incrementAndGet();
                    }
                })
                .build();
    }

    /** Whether this change is new, remembering it: of two sessions offering the same, one gets true. */
    boolean first(long producerId, String eventId, long timestamp) {
        return delivered.asMap().putIfAbsent(producerId + "_" + eventId + "_" + timestamp, Boolean.TRUE) == null;
    }

    /** Changes forgotten for room before their hour was up. */
    public long evicted() {
        return evicted.get();
    }

    /** Runs what the cache has pending, for a test that counts. */
    void cleanUp() {
        delivered.cleanUp();
    }
}
