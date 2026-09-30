package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A bounded cache of entities by key: at most {@code maximumSize} entries, each dropped {@code age}
 * after it last changed, and each locale of an entry fresh for {@code age} after its last
 * authoritative fetch, whatever the other locales. It does no I/O and never waits for anything but
 * the lock of the one key it writes.
 *
 * <p>Writes follow the write rule: an authoritative response replaces and marks the fields its
 * endpoint is authoritative for, unless the entry was invalidated since its fetch started; a
 * response from any other endpoint only fills what is absent and unmarked.
 *
 * <p>Safe for concurrent use.
 */
public final class EntityCache<K> {

    private final String name;
    private final Duration age;
    private final InstantSource clock;
    private final Ticker ticker;
    private final Cache<K, Entry> entries;

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

    /** Whether the entry has {@code locale} from an authoritative fetch younger than the cache's age. */
    public boolean isFresh(K key, Locale locale) {
        Entry entry = entries.getIfPresent(key);
        return entry != null && entry.isFresh(locale, clock.instant(), age);
    }

    /** What an authoritative fetch remembers when it starts, to tell whether its result still applies. */
    public Stamp stamp(K key) {
        Entry entry = entries.getIfPresent(key);
        return entry == null ? Stamp.ABSENT : new Stamp(true, entry.generation());
    }

    /**
     * Writes an authoritative response, unless the entry was invalidated or dropped since {@code
     * started}: then nothing is written, and the caller reads the entry again.
     *
     * @return whether it was written
     */
    public boolean writeAuthoritative(K key, Write write, Stamp started) {
        var written = new boolean[1];
        entries.asMap().compute(key, (k, current) -> {
            if (started.present()
                    ? current == null || current.generation() != started.generation()
                    : current != null && current.generation() != 0) {
                return current;
            }
            written[0] = true;
            return (current == null ? Entry.ABSENT : current).authoritative(write, clock.instant(), ticker.read());
        });
        return written[0];
    }

    /** Writes what another endpoint carries: only fields absent and never marked authoritative. */
    public void fill(K key, Write write) {
        entries.asMap().compute(key, (k, current) -> {
            Entry filled = (current == null ? Entry.ABSENT : current).fill(write, ticker.read());
            // a fill that adds nothing to nothing leaves nothing behind
            return current == null && filled == Entry.ABSENT ? null : filled;
        });
    }

    /**
     * Drops the entry's values and leaves a tombstone with a new generation, so a fetch that started
     * before throws its result away.
     */
    public void invalidate(K key) {
        entries.asMap()
                .compute(key, (k, current) -> (current == null ? Entry.ABSENT : current).invalidated(ticker.read()));
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

    /**
     * The state of an entry when a fetch started.
     *
     * @param present whether there was an entry
     * @param generation its generation then
     */
    public record Stamp(boolean present, long generation) {
        static final Stamp ABSENT = new Stamp(false, 0);
    }
}
