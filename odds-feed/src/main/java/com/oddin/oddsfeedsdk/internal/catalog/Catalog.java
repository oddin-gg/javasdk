package com.oddin.oddsfeedsdk.internal.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.loader.Loader;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Values a catalog endpoint is asked for by key - a locale's list of market descriptions, one
 * dynamic market variant in one locale - that refresh after write, at most {@code maximumSize} of
 * them.
 *
 * <ul>
 *   <li>A value younger than the refresh age is served as it is.
 *   <li>An older one is served still, and a refresh starts in the background; it is served while the
 *       refresh runs and while it fails, until it is as old as the maximum staleness. Past that it
 *       counts as missing.
 *   <li>A read with nothing to serve fetches and waits, and fails when the fetch does.
 *   <li>A failed fetch backs off its key, from a second doubling to a minute: until then no refresh
 *       of it starts, and a read with nothing to serve fails at once with the failure it had.
 * </ul>
 *
 * <p>Ages count from when the fetch started, so they are the ages of the data. Fetching is
 * single-flight through a {@link Loader}: a read, a refresh and a reload of one key share one
 * fetch, under one deadline. A clear drops what is held, and a fetch that started before it writes
 * nothing.
 *
 * <p>Safe for concurrent use.
 */
final class Catalog<K, V> {

    /** Fetches the value of one key. */
    @FunctionalInterface
    interface Fetch<K, V> {
        /**
         * Fetches the key's value, failing when it cannot.
         *
         * @param previous what is held for the key, however old, or null: a fetch can refuse an
         *     answer that would replace it with nothing
         * @param deadline the fetch's one deadline, for every REST call it makes
         */
        V fetch(K key, @Nullable V previous, Deadline deadline);
    }

    /** Looks up one item, such as a market, in a value, such as a locale's list. */
    @FunctionalInterface
    interface Finder<V, I, R> {
        @Nullable
        R find(V value, I item);
    }

    /** How long a failed fetch backs its key off at first; it doubles with each failure in a row. */
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    /** The longest a failed fetch backs its key off. */
    static final Duration LONGEST_BACKOFF = Duration.ofMinutes(1);

    /**
     * How old a value must be before an item missing from it refreshes it early: a market new
     * upstream shows up within about this long, and a burst of unknown items costs one fetch a
     * minute at most.
     */
    static final Duration MISS_INTERVAL = Duration.ofMinutes(1);

    /** How many missed items are remembered, so that each refreshes its value once. */
    static final long MISSES = 10_000;

    /** How much longer than its deadline a reader waits for a fetch. */
    static final Duration MARGIN = Duration.ofSeconds(1);

    private final String name;
    private final Duration refreshAge;
    private final Duration maxStaleness;
    private final Fetch<K, V> fetch;
    private final InstantSource clock;
    private final Executor refreshes;
    private final Loader<K, V> loader;
    private final Cache<K, Held<V>> held;
    /** The last failure of each key whose last fetch failed. */
    private final Cache<K, Failure> failures;
    /** When each item was first found missing, in {@link #order}, to refresh its value once for it. */
    private final Cache<Miss<K>, Long> misses;
    /**
     * Orders the starts of fetches and the first misses of items, which the clock cannot: a fetch
     * and a miss can fall on one instant.
     */
    private final AtomicLong order = new AtomicLong();
    /** The keys a background refresh was started for and has not ended. */
    private final Set<K> refreshing = ConcurrentHashMap.newKeySet();
    /** When each key held was first served stale, until a fetch writes it again. */
    private final ConcurrentHashMap<K, Instant> staleSince = new ConcurrentHashMap<>();
    /** Bumped by every clear: a fetch that started under an older one writes nothing. */
    private final AtomicLong generation = new AtomicLong();
    /**
     * Shared by the writes, held alone by a clear: a write that has passed its check finishes before
     * the clear drops anything, and every write after it sees the clear.
     */
    private final ReentrantReadWriteLock clearing = new ReentrantReadWriteLock();

    private final AtomicLong servedStale = new AtomicLong();
    private final AtomicLong failedFetches = new AtomicLong();
    private final AtomicLong evictedForRoom = new AtomicLong();

    /**
     * @param timeout the HTTP client timeout, each fetch's deadline
     * @param fetches where the fetches run: virtual threads
     * @param refreshes where a background refresh waits for its fetch: virtual threads
     */
    Catalog(
            String name,
            long maximumSize,
            Duration refreshAge,
            Duration maxStaleness,
            Fetch<K, V> fetch,
            Duration timeout,
            Executor fetches,
            Executor refreshes,
            InstantSource clock,
            Ticker ticker) {
        if (refreshAge.compareTo(maxStaleness) >= 0) {
            throw new IllegalArgumentException(name + ": the refresh age " + refreshAge
                    + " leaves nothing to serve stale before the maximum staleness " + maxStaleness);
        }
        this.name = name;
        this.refreshAge = refreshAge;
        this.maxStaleness = maxStaleness;
        this.fetch = fetch;
        this.clock = clock;
        this.refreshes = refreshes;
        this.loader = new Loader<>(name, this::fetch, timeout, MARGIN, fetches);
        this.held = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                // a bound on memory only, a little after a value can no longer be served: a read
                // judges its age by when its fetch started
                .expireAfterWrite(maxStaleness.plus(MARGIN))
                .ticker(ticker)
                // eviction on the writing thread: nothing to hand over, nothing left pending
                .executor(Runnable::run)
                .<K, Held<V>>evictionListener((key, value, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictedForRoom.incrementAndGet();
                    }
                    if (key != null) {
                        staleSince.remove(key);
                    }
                })
                .build();
        this.failures = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(maxStaleness)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
        this.misses = Caffeine.newBuilder()
                .maximumSize(MISSES)
                .expireAfterWrite(refreshAge)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    String name() {
        return name;
    }

    /**
     * The key's value: what is held when it is not older than the maximum staleness - a refresh
     * started when it is older than the refresh age - else fetched now.
     *
     * @throws ApiException when there is nothing to serve and the fetch fails, or the key is
     *     backing off
     */
    V get(K key) {
        Instant now = clock.instant();
        Held<V> current = usable(key, now);
        if (current == null) {
            return fetchNow(key, now);
        }
        if (current.fetchedAt().plus(refreshAge).isBefore(now)) {
            servedStale.incrementAndGet();
            staleSince.putIfAbsent(key, now);
            refreshInBackground(key, now);
        }
        return current.value();
    }

    /**
     * An item of the key's value, such as one market of a locale's list. When the value lacks it,
     * the value is fetched again - once per item, and only when it was fetched before the item was
     * first missed and at least {@link #MISS_INTERVAL} ago - so that what is new upstream shows up
     * before the next refresh. Null when it is not there even then, or that fetch fails.
     *
     * @throws ApiException when there is no value to look in and the fetch fails
     */
    <I, R> @Nullable R find(K key, I item, Finder<V, I, R> finder) {
        R found = finder.find(get(key), item);
        if (found != null) {
            return found;
        }
        Instant now = clock.instant();
        long firstMissed = misses.asMap().computeIfAbsent(new Miss<>(key, item), _ -> order.incrementAndGet());
        Held<V> current = held.getIfPresent(key);
        if (current == null
                || current.startedAs() > firstMissed
                || current.fetchedAt().plus(MISS_INTERVAL).isAfter(now)
                || backingOff(key, now) != null) {
            return null;
        }
        try {
            return finder.find(loader.load(key), item);
        } catch (ApiException failed) {
            // counted where it failed; the item is as unknown as it was
            return null;
        }
    }

    /**
     * The key's value fetched now, whatever is held and whether or not the key is backing off, as
     * a client's reload asks. What was held stays when the fetch fails.
     *
     * @throws ApiException when the fetch fails
     */
    V reload(K key) {
        return loader.load(key);
    }

    /** What is held for the key and not older than the maximum staleness, fetching nothing. */
    @Nullable
    V peek(K key) {
        Held<V> current = usable(key, clock.instant());
        return current == null ? null : current.value();
    }

    /** Every value held that is not older than the maximum staleness, fetching nothing. */
    Map<K, V> peekAll() {
        Instant now = clock.instant();
        var all = new HashMap<K, V>();
        held.asMap().forEach((key, value) -> {
            if (!tooOld(value, now)) {
                all.put(key, value.value());
            }
        });
        return all;
    }

    /** Drops the values of the keys {@code which} accepts; a fetch under way then writes nothing. */
    void clear(Predicate<? super K> which) {
        clearing.writeLock().lock();
        try {
            generation.incrementAndGet();
            for (K key : List.copyOf(held.asMap().keySet())) {
                if (which.test(key)) {
                    held.invalidate(key);
                    staleSince.remove(key);
                }
            }
        } finally {
            clearing.writeLock().unlock();
        }
    }

    /** Drops every value; a fetch under way then writes nothing. */
    void clear() {
        clear(_ -> true);
    }

    CatalogHealth health() {
        Instant now = clock.instant();
        Duration staleFor = Duration.ZERO;
        for (Instant since : staleSince.values()) {
            Duration stale = Duration.between(since, now);
            if (stale.compareTo(staleFor) > 0) {
                staleFor = stale;
            }
        }
        failures.cleanUp();
        return new CatalogHealth(
                name, servedStale.get(), staleFor, failedFetches.get(), failures.estimatedSize(), evictedForRoom.get());
    }

    private @Nullable Held<V> usable(K key, Instant now) {
        Held<V> current = held.getIfPresent(key);
        return current == null || tooOld(current, now) ? null : current;
    }

    private boolean tooOld(Held<V> value, Instant now) {
        return value.fetchedAt().plus(maxStaleness).isBefore(now);
    }

    private V fetchNow(K key, Instant now) {
        Failure failure = backingOff(key, now);
        if (failure != null) {
            throw new ApiException(
                    name + " " + key + ": not fetched again before " + failure.retryAt() + ", " + failure.attempts()
                            + " fetches in a row failed, the last at " + failure.at(),
                    null,
                    failure.cause());
        }
        return loader.load(key);
    }

    private void refreshInBackground(K key, Instant now) {
        if (backingOff(key, now) != null || !refreshing.add(key)) {
            return;
        }
        try {
            refreshes.execute(() -> {
                try {
                    loader.load(key);
                } catch (RuntimeException failed) {
                    // counted where it failed; the stale value is served until the next try
                } finally {
                    refreshing.remove(key);
                }
            });
        } catch (RejectedExecutionException closed) {
            refreshing.remove(key);
        }
    }

    /** The key's last failure while it still backs the key off, else null. */
    private @Nullable Failure backingOff(K key, Instant now) {
        Failure failure = failures.getIfPresent(key);
        return failure != null && failure.retryAt().isAfter(now) ? failure : null;
    }

    /** One fetch, as the loader runs it: the value written unless a clear or a newer fetch came first. */
    private V fetch(K key, Deadline deadline, BooleanSupplier abandoned) {
        long startedIn = generation.get();
        long startedAs = order.incrementAndGet();
        Instant startedAt = clock.instant();
        Held<V> previous = held.getIfPresent(key);
        V value;
        try {
            value = fetch.fetch(key, previous == null ? null : previous.value(), deadline);
        } catch (RuntimeException e) {
            failedFetches.incrementAndGet();
            if (!abandoned.getAsBoolean()) {
                Instant failedAt = clock.instant();
                failures.asMap()
                        .merge(
                                key,
                                new Failure(failedAt, 1, e),
                                (last, _) -> new Failure(failedAt, last.attempts() + 1, e));
            }
            throw e;
        }
        clearing.readLock().lock();
        try {
            if (!abandoned.getAsBoolean() && generation.get() == startedIn) {
                held.asMap()
                        .compute(
                                key,
                                (k, current) -> current != null && current.startedAs() > startedAs
                                        ? current
                                        : new Held<>(value, startedAt, startedAs));
                failures.invalidate(key);
                staleSince.remove(key);
            }
        } finally {
            clearing.readLock().unlock();
        }
        // the callers have what they asked for, written or not
        return value;
    }

    /** A value, and when the fetch that got it started: on the clock, and in {@link #order}. */
    private record Held<V>(V value, Instant fetchedAt, long startedAs) {}

    /** A key's last failed fetch, how many failed in a row, and when the next may start. */
    private record Failure(Instant at, int attempts, RuntimeException cause) {
        Instant retryAt() {
            long nanos = FIRST_BACKOFF.toNanos() << Math.min(attempts - 1, 30);
            return at.plus(nanos > LONGEST_BACKOFF.toNanos() ? LONGEST_BACKOFF : Duration.ofNanos(nanos));
        }
    }

    /** An item missing from a key's value. */
    private record Miss<K>(K key, Object item) {}
}
