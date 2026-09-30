package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A bounded cache of entities by key: at most {@code maximumSize} entries, each dropped {@code age}
 * after it last changed, and each authoritative endpoint of an entry fresh in a locale for {@code
 * age} after its own fetch in that locale, whatever the others. It does no I/O and never waits for
 * anything but the lock of the one key it writes.
 *
 * <p>Writes follow the write rule: an authoritative response replaces and marks the fields its
 * endpoint is authoritative for, unless the entry was invalidated since its fetch started; a
 * response from any other endpoint only fills what is absent and unmarked.
 *
 * <p>Safe for concurrent use.
 */
public final class EntityCache<K> {

    /** How long an invalidation is remembered for a key with no entry: longer than any fetch waits. */
    static final Duration INVALIDATION_MEMORY = Duration.ofHours(1);

    private final String name;
    private final Duration age;
    private final InstantSource clock;
    private final Ticker ticker;
    private final Cache<K, Entry> entries;
    /** Where generations come from: no two entries of this cache, past or present, share one. */
    private final AtomicLong generations = new AtomicLong();
    /**
     * The generation of each key's last invalidation: what a fetch that started on no entry checks,
     * since there is no entry to tell it, and what outlasts a tombstone that was evicted.
     */
    private final Cache<K, Long> invalidated;

    public EntityCache(String name, long maximumSize, Duration age) {
        this(name, maximumSize, age, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives: one for fetch times, one for Caffeine's ages. */
    EntityCache(String name, long maximumSize, Duration age, InstantSource clock, Ticker ticker) {
        this.name = name;
        this.age = age;
        this.clock = clock;
        this.ticker = ticker;
        this.entries = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new AgedFromLastChange<K, Entry>(age, Entry::changedAt))
                .ticker(ticker)
                // eviction on the writing thread: nothing to hand over, nothing left pending
                .executor(Runnable::run)
                .build();
        this.invalidated = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(INVALIDATION_MEMORY)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    public String name() {
        return name;
    }

    public Duration age() {
        return age;
    }

    /** The entry, a tombstone included, or null when there is none. */
    public @Nullable Entry get(K key) {
        return entries.getIfPresent(key);
    }

    /** Whether {@code endpoint} was fetched for the entry in {@code locale} within the cache's age. */
    public boolean isFresh(K key, Endpoint endpoint, Locale locale) {
        Entry entry = entries.getIfPresent(key);
        return entry != null && entry.isFresh(endpoint, locale, clock.instant(), age);
    }

    /** What an authoritative fetch remembers when it starts, to tell whether its result still applies. */
    public Stamp stamp(K key) {
        long lastInvalidation = lastInvalidation(key);
        Entry entry = entries.getIfPresent(key);
        return entry == null
                ? new Stamp(false, 0, lastInvalidation)
                : new Stamp(true, entry.generation(), lastInvalidation);
    }

    /**
     * Writes an authoritative response, unless the entry was invalidated or dropped since {@code
     * started}: then nothing is written, and the caller reads the entry again. Generations are never
     * reused, so an entry dropped and made again is not taken for the one the fetch started with,
     * and a fetch that started on no entry gives way to an invalidation of its key since.
     *
     * @return whether it was written
     */
    public boolean writeAuthoritative(K key, Write write, Stamp started) {
        var written = new boolean[1];
        entries.asMap().compute(key, (k, current) -> {
            boolean stale = started.present()
                    ? current == null || current.generation() != started.generation()
                    : lastInvalidation(key) != started.lastInvalidation();
            if (stale) {
                return current;
            }
            written[0] = true;
            Entry base = current == null ? Entry.empty(generations.incrementAndGet()) : current;
            return base.authoritative(write, clock.instant(), ticker.read());
        });
        return written[0];
    }

    /** Writes what another endpoint carries: only fields absent and never marked authoritative. */
    public void fill(K key, Write write) {
        entries.asMap().compute(key, (k, current) -> {
            Entry base = current == null ? Entry.empty(generations.incrementAndGet()) : current;
            Entry filled = base.fill(write, ticker.read());
            // a fill that adds nothing to nothing leaves nothing behind
            return current == null && filled == base ? null : filled;
        });
    }

    /**
     * Drops the entry's values and leaves a tombstone with a new generation, so a fetch that started
     * before throws its result away. A key with no entry gets no tombstone: its invalidation is
     * remembered for as long as a fetch can run.
     */
    public void invalidate(K key) {
        long generation = generations.incrementAndGet();
        // remembered before the tombstone is there, so no fetch can miss both
        invalidated.put(key, generation);
        entries.asMap().computeIfPresent(key, (k, current) -> current.invalidated(generation, ticker.read()));
    }

    /** Invalidates every entry. */
    public void clear() {
        for (K key : List.copyOf(entries.asMap().keySet())) {
            invalidate(key);
        }
    }

    /** How many entries it holds, tombstones included. */
    public long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    private long lastInvalidation(K key) {
        Long generation = invalidated.getIfPresent(key);
        return generation == null ? 0 : generation;
    }

    /**
     * The state of an entry when a fetch started.
     *
     * @param present whether there was an entry
     * @param generation its generation then
     * @param lastInvalidation the generation of the key's last invalidation then, 0 for none
     */
    public record Stamp(boolean present, long generation, long lastInvalidation) {}
}
