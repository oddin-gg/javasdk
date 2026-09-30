package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.function.ToLongFunction;

/**
 * Expires a value its age after it last changed. Caffeine counts every {@code compute} as a write,
 * even one that returns the value it was given, so expire-after-write would let a rejected message
 * or a fill that added nothing keep an entry alive. Each value carries the time of its last real
 * change instead, on the cache's ticker, and ages from that.
 */
final class AgedFromLastChange<K, V> implements Expiry<K, V> {

    private final long ageNanos;
    private final ToLongFunction<V> changedAt;

    AgedFromLastChange(Duration age, ToLongFunction<V> changedAt) {
        this.ageNanos = age.toNanos();
        this.changedAt = changedAt;
    }

    @Override
    public long expireAfterCreate(K key, V value, long currentTime) {
        return remaining(value, currentTime);
    }

    @Override
    public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
        return remaining(value, currentTime);
    }

    @Override
    public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
        return currentDuration;
    }

    private long remaining(V value, long currentTime) {
        return Math.max(0, ageNanos - (currentTime - changedAt.applyAsLong(value)));
    }
}
