package com.oddin.oddsfeedsdk.internal.catalog;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** One clock for a test to move: the fetch times the entries keep, and Caffeine's ages. */
final class FakeTime implements InstantSource, Ticker {

    private volatile Instant now = Instant.parse("2026-09-30T12:00:00Z");

    private final FeedClock clock = FeedClock.following(this);

    /** The feed's clock over this one: both sides move together, as the caches read them. */
    FeedClock clock() {
        return clock;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public long read() {
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    void advance(Duration by) {
        now = now.plus(by);
    }
}
