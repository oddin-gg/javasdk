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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 *       refresh runs and for as long as refreshes fail, however old it gets, as 0.0.x served what it
 *       had. Its health says how long it has been served stale. A value goes only when a fetch
 *       replaces it, a clear drops it, or the size bound evicts it.
 *   <li>A read with nothing to serve fetches and waits, and fails when the fetch does.
 *   <li>A failed fetch backs off its key, from a second doubling to a minute: until then no refresh
 *       of it starts, and a read with nothing to serve fails at once with the failure it had.
 * </ul>
 *
 * <p>Ages count from when the fetch started, so they are the ages of the data. Fetching is
 * single-flight through a {@link Loader}: the reads and the refreshes of one key share one fetch,
 * under one deadline; a reload has its own. A clear drops what is held and the backoff of the keys
 * it clears. A fetch of such a key asked for before it writes nothing, neither a value nor a
 * failure, even one still waiting to run, and a read after it does not join that fetch but starts
 * its own; the fetches of the other keys go on as they were.
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
    private final Fetch<K, V> fetch;
    private final InstantSource clock;
    private final BooleanSupplier closed;
    private final Executor refreshes;
    private final Loader<Flight<K>, V> loader;
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
    /** Bumped by every clear: where the generations of the clears come from. */
    private final AtomicLong generations = new AtomicLong();
    /** The generation of the last clear of everything. */
    private final AtomicLong clearedAll = new AtomicLong();
    /**
     * The generation of each key's last clear of its own, remembered as long as a fetch from before
     * it can run: a fetch of the key that started under an older generation writes nothing, and a
     * clear of one key leaves the fetches of the others alone. One dropped for room counts as a
     * clear of everything, so no fetch from before it can write.
     */
    private final Cache<K, Long> clearedAt;
    /**
     * Shared by the writes, held alone by a clear: a write that has passed its check finishes before
     * the clear drops anything, and every write after it sees the clear.
     */
    private final ReentrantReadWriteLock clearing = new ReentrantReadWriteLock();

    /** A test's hook: runs in a read with nothing to serve, before it asks the loader for a fetch. */
    volatile Runnable insideColdRead = () -> {};
    /** A test's hook: runs in a read that did not find its item, before it notes the miss. */
    volatile Runnable insideMiss = () -> {};
    /** A test's hook: runs in a read that found its value stale, before it marks it so. */
    volatile Runnable insideStaleRead = () -> {};

    private final AtomicLong servedStale = new AtomicLong();
    private final AtomicLong failedFetches = new AtomicLong();
    private final AtomicLong evictedForRoom = new AtomicLong();

    /**
     * @param timeout the HTTP client timeout, each fetch's deadline
     * @param fetches where the fetches run: virtual threads
     * @param refreshes where a background refresh waits for its fetch: virtual threads
     * @param closed whether the feed's API client is closed: a fetch that fails then is no failure
     */
    Catalog(
            String name,
            long maximumSize,
            Duration refreshAge,
            Fetch<K, V> fetch,
            Duration timeout,
            Executor fetches,
            Executor refreshes,
            InstantSource clock,
            Ticker ticker,
            BooleanSupplier closed) {
        this.name = name;
        this.refreshAge = refreshAge;
        this.fetch = fetch;
        this.clock = clock;
        this.refreshes = refreshes;
        this.closed = closed;
        this.loader = new Loader<>(name, this::fetch, timeout, MARGIN, fetches);
        this.held = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                // eviction on the writing thread: nothing to hand over, nothing left pending
                .executor(Runnable::run)
                .<K, Held<V>>evictionListener((key, value, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictedForRoom.incrementAndGet();
                    }
                })
                .build();
        this.failures = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                // a failure nobody tried again for this long is forgotten: a key read now and then is
                // tried at least once a minute while it fails
                .expireAfterWrite(refreshAge)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
        this.clearedAt = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                // abandoned past its deadline and margin, no fetch from before a clear writes later
                .expireAfterWrite(timeout.plus(MARGIN).plus(MARGIN))
                .ticker(ticker)
                .executor(Runnable::run)
                .<K, Long>evictionListener((key, generation, cause) -> {
                    if (cause == RemovalCause.SIZE && generation != null) {
                        clearedAll.accumulateAndGet(generation, Math::max);
                    }
                })
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
     * The key's value: what is held, however old - a refresh started when it is older than the
     * refresh age - else fetched now.
     *
     * @throws ApiException when there is nothing to serve and the fetch fails, or the key is
     *     backing off
     */
    V get(K key) {
        Held<V> current = held.getIfPresent(key);
        return current == null ? fetchNow(key) : served(key, current);
    }

    /**
     * What is held for the key, as {@link #get} serves it - a refresh started when it is older than
     * the refresh age, and the read counted stale - or null when nothing is held; it fetches nothing.
     */
    @Nullable
    V getHeld(K key) {
        Held<V> current = held.getIfPresent(key);
        return current == null ? null : served(key, current);
    }

    /** A held value served: when it is older than the refresh age, marked stale and refreshed. */
    private V served(K key, Held<V> current) {
        Instant now = clock.instant();
        if (current.fetchedAt().plus(refreshAge).isBefore(now)) {
            servedStale.incrementAndGet();
            insideStaleRead.run();
            // on the value served, so a value replaced, cleared or evicted since takes its mark along
            current.staleSince().compareAndSet(null, now);
            current.lastStaleRead().set(now);
            refreshInBackground(key, now);
        }
        return current.value();
    }

    /**
     * An item of the key's value, such as one market of a locale's list. When the value lacks it,
     * the value is fetched again and the read waits for it, as a read with nothing to serve does -
     * once per item, and only when the value was fetched before the item was first missed and at
     * least {@link #MISS_INTERVAL} ago - so that what is new upstream is found by the read that first
     * asks for it, as in 0.0.x. While the key's last fetch failed, the read does not wait: it starts a
     * refresh in the background, unless the key backs off, and the item is found by a later read.
     * Null when it is not there even then, or that fetch fails.
     *
     * @throws ApiException when there is no value to look in and the fetch fails
     */
    <I, R> @Nullable R find(K key, I item, Finder<V, I, R> finder) {
        R found = finder.find(get(key), item);
        if (found != null) {
            return found;
        }
        insideMiss.run();
        Instant now = clock.instant();
        long firstMissed = misses.asMap().computeIfAbsent(new Miss<>(key, item), _ -> order.incrementAndGet());
        Held<V> current = held.getIfPresent(key);
        if (current == null) {
            return null;
        }
        // a fetch since may have brought it; looking again costs less than telling the values apart
        found = finder.find(current.value(), item);
        if (found != null) {
            return found;
        }
        if (current.startedAs() > firstMissed
                || current.fetchedAt().plus(MISS_INTERVAL).isAfter(now)) {
            return null;
        }
        if (failures.getIfPresent(key) != null) {
            // the API failed the last time: a reader waiting on it would wait a timeout each time the
            // backoff ends, for as long as an outage lasts
            refreshInBackground(key, now);
            return null;
        }
        try {
            return finder.find(fetchNow(key), item);
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
        return loader.load(new Flight<>(key, generationOf(key), true, false));
    }

    /**
     * The key's value fetched unless it is held, for no reader: what the feed's open does for its
     * preload locales. Unlike a read's, a failure of this fetch backs the key off for no one, so the
     * first read after it fetches as it would have without the preload; it is counted all the same.
     * No reader joins it, nor does it join a read's.
     *
     * @throws ApiException when the fetch fails, or the key backs off from a read's failure
     */
    void preload(K key) {
        if (held.getIfPresent(key) == null) {
            loader.load(new Flight<>(key, generationOf(key), false, true));
        }
    }

    /** What is held for the key, fetching nothing. */
    @Nullable
    V peek(K key) {
        Held<V> current = held.getIfPresent(key);
        return current == null ? null : current.value();
    }

    /** Every value held, fetching nothing. */
    Map<K, V> peekAll() {
        var all = new HashMap<K, V>();
        held.asMap().forEach((key, value) -> all.put(key, value.value()));
        return all;
    }

    /**
     * Drops the values of the keys {@code which} accepts, and their backoff, so the next read fetches
     * them; a fetch of one of them asked for before, running or still waiting to run, then writes
     * nothing, neither a value nor a failure. The other keys and their fetches are left alone.
     */
    void clear(Predicate<? super K> which) {
        clearing.writeLock().lock();
        try {
            long generation = generations.incrementAndGet();
            var keys = new HashSet<K>(held.asMap().keySet());
            keys.addAll(failures.asMap().keySet());
            // a fetch is the loader's from when its first reader asks, before it runs: one not there
            // was asked for after this clear began, and asks the API after it
            for (Flight<K> flight : loader.keys()) {
                keys.add(flight.key());
            }
            for (K key : keys) {
                if (which.test(key)) {
                    clearedAt.put(key, generation);
                    held.invalidate(key);
                    failures.invalidate(key);
                }
            }
        } finally {
            clearing.writeLock().unlock();
        }
    }

    /** Drops every value; every fetch under way then writes nothing. */
    void clear() {
        clearing.writeLock().lock();
        try {
            clearedAll.set(generations.incrementAndGet());
            held.invalidateAll();
            failures.invalidateAll();
        } finally {
            clearing.writeLock().unlock();
        }
    }

    CatalogHealth health() {
        Instant now = clock.instant();
        Duration staleFor = Duration.ZERO;
        // only the values held now, and still read: one replaced, cleared or evicted is stale no
        // longer, and one nobody read for the refresh age is not being served
        for (Held<V> value : held.asMap().values()) {
            Instant since = value.staleSince().get();
            Instant lastRead = value.lastStaleRead().get();
            if (since != null
                    && lastRead != null
                    && !lastRead.plus(refreshAge).isBefore(now)
                    && Duration.between(since, now).compareTo(staleFor) > 0) {
                staleFor = Duration.between(since, now);
            }
        }
        failures.cleanUp();
        return new CatalogHealth(
                name, servedStale.get(), staleFor, failedFetches.get(), failures.estimatedSize(), evictedForRoom.get());
    }

    /** A fetch the reader waits for; one that would start while the key backs off fails at once. */
    private V fetchNow(K key) {
        insideColdRead.run();
        return loader.load(new Flight<>(key, generationOf(key), false, false));
    }

    private void refreshInBackground(K key, Instant now) {
        if (backingOff(key, now) != null || !refreshing.add(key)) {
            return;
        }
        try {
            refreshes.execute(() -> {
                try {
                    loader.load(new Flight<>(key, generationOf(key), false, false));
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

    private ApiException backingOff(K key, Failure failure) {
        return new ApiException(
                name + " " + key + ": not fetched again before " + failure.retryAt() + ", " + failure.attempts()
                        + " fetches in a row failed, the last at " + failure.at(),
                null,
                failure.cause());
    }

    /** The generation of the key's last clear, its own or of everything; 0 for none. */
    private long generationOf(K key) {
        Long own = clearedAt.getIfPresent(key);
        return Math.max(clearedAll.get(), own == null ? 0 : own);
    }

    /** The key's last failure while it still backs the key off, else null. */
    private @Nullable Failure backingOff(K key, Instant now) {
        Failure failure = failures.getIfPresent(key);
        return failure != null && failure.retryAt().isAfter(now) ? failure : null;
    }

    /**
     * One fetch, as the loader runs it: the value written unless a clear or a newer fetch came first.
     * A flight that is no reload does not start while its key backs off. It is checked here, as the
     * flight starts, because a reader that checked before can be late: a flight that failed in the
     * meantime has recorded its failure before it left the loader.
     */
    private V fetch(Flight<K> flight, Deadline deadline, BooleanSupplier abandoned) {
        K key = flight.key();
        if (!flight.reload()) {
            Failure failure = backingOff(key, clock.instant());
            if (failure != null) {
                throw backingOff(key, failure);
            }
        }
        long startedIn = flight.generation();
        long startedAs = order.incrementAndGet();
        Instant startedAt = clock.instant();
        Held<V> previous = held.getIfPresent(key);
        V value;
        try {
            value = fetch.fetch(key, previous == null ? null : previous.value(), deadline);
        } catch (RuntimeException e) {
            // a fetch the feed's close cut short failed of the shutdown:
            // no failure to count or back off from, as the preloads of the feed say
            boolean shutdown = closed.getAsBoolean();
            if (!shutdown) {
                failedFetches.incrementAndGet();
            }
            clearing.readLock().lock();
            try {
                // a fetch from before a clear backs nothing off: the clear asked for a fetch; nor does a
                // preload, which no reader waited for
                if (!shutdown && !flight.preload() && !abandoned.getAsBoolean() && generationOf(key) <= startedIn) {
                    Instant failedAt = clock.instant();
                    // an older fetch that fails after a newer one succeeded, or failed, records nothing
                    failures.asMap().compute(key, (k, last) -> {
                        Held<V> newer = held.getIfPresent(k);
                        if ((newer != null && newer.startedAs() > startedAs)
                                || (last != null && last.startedAs() > startedAs)) {
                            return last;
                        }
                        return new Failure(failedAt, last == null ? 1 : last.attempts() + 1, e, startedAs);
                    });
                }
            } finally {
                clearing.readLock().unlock();
            }
            throw e;
        }
        clearing.readLock().lock();
        try {
            if (!abandoned.getAsBoolean() && generationOf(key) <= startedIn) {
                held.asMap()
                        .compute(
                                key,
                                (k, current) -> current != null && current.startedAs() > startedAs
                                        ? current
                                        : new Held<>(
                                                value,
                                                startedAt,
                                                startedAs,
                                                new AtomicReference<>(),
                                                new AtomicReference<>()));
                // a newer fetch's failure stands
                failures.asMap().computeIfPresent(key, (k, last) -> last.startedAs() > startedAs ? last : null);
            }
        } finally {
            clearing.readLock().unlock();
        }
        // the callers have what they asked for, written or not
        return value;
    }

    /**
     * A value, when the fetch that got it started - on the clock, and in {@link #order} - and when it
     * was first and last served stale, null until it is.
     */
    private record Held<V>(
            V value,
            Instant fetchedAt,
            long startedAs,
            AtomicReference<@Nullable Instant> staleSince,
            AtomicReference<@Nullable Instant> lastStaleRead) {}

    /**
     * A key's last failed fetch, how many failed in a row, when the next may start, and when the
     * fetch started, in {@link #order}.
     */
    private record Failure(Instant at, int attempts, RuntimeException cause, long startedAs) {
        Instant retryAt() {
            long nanos = FIRST_BACKOFF.toNanos() << Math.min(attempts - 1, 30);
            return at.plus(nanos > LONGEST_BACKOFF.toNanos() ? LONGEST_BACKOFF : Duration.ofNanos(nanos));
        }
    }

    /**
     * What the loader fetches: a key, in its generation when its reader asked, so that a read after a
     * clear of the key never joins a fetch from before it, and a clear of another key changes nothing;
     * whether it is a reload, which does not wait out a backoff; and whether it is a preload, whose
     * failure backs nothing off, since no reader waited for it.
     */
    private record Flight<K>(K key, long generation, boolean reload, boolean preload) {
        @Override
        public String toString() {
            return key.toString();
        }
    }

    /** An item missing from a key's value. */
    private record Miss<K>(K key, Object item) {}
}
