package com.oddin.oddsfeedsdk.internal.dispatch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fixture changes the feed's sessions have delivered, so the same one reaches the client once,
 * whichever session it comes in: one map per feed, keyed as 0.0.x keyed it - producer, event id and
 * the change's timestamp - and remembered for an hour. The event id is the parsed one, and one longer
 * than {@value #MAX_EVENT_ID} characters, which no event of the feed has, is not remembered: what
 * the map holds is bounded in bytes as well as in entries, whatever the feed's text holds. It is
 * bounded; a change dropped for room before its hour is counted, and would be delivered again if it
 * came again.
 *
 * <p>Safe for concurrent use.
 */
public final class FixtureChanges {

    static final Duration AGE = Duration.ofHours(1);
    static final long SIZE = 100_000;
    /** The longest event id remembered: a tournament's, with the largest number, is 33. */
    static final int MAX_EVENT_ID = 64;

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

    /**
     * Whether this change is new, remembering it: of two sessions offering the same, one gets true. An
     * event id too long to remember is new every time.
     */
    boolean first(long producerId, URN eventId, long timestamp) {
        String id = eventId.toString();
        if (id.length() > MAX_EVENT_ID) {
            return true;
        }
        return delivered.asMap().putIfAbsent(producerId + "_" + id + "_" + timestamp, Boolean.TRUE) == null;
    }

    /** Changes forgotten for room before their hour was up. */
    public long evicted() {
        return evicted.get();
    }

    /** The changes remembered now, for a test. */
    long size() {
        delivered.cleanUp();
        return delivered.estimatedSize();
    }

    /** Runs what the cache has pending, for a test that counts. */
    void cleanUp() {
        delivered.cleanUp();
    }
}
