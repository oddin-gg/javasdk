package com.oddin.oddsfeedsdk.internal.loader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Single-flight fetching by key: concurrent misses for one key share one fetch, run off the
 * callers' threads. A fetch has one deadline, the HTTP client timeout from when it starts, and every
 * REST call it makes draws from it; every caller waits at most until that deadline plus a margin,
 * however late it joined, and a caller that runs out of time fails - it never starts a fetch of its
 * own. A fetch that fails fails every caller waiting for it, and leaves before it tells anyone how
 * it went. One still running past its callers' time is abandoned, writes nothing from then on, and
 * is replaced by the next miss.
 *
 * <p>A key whose fetch failed backs off: a miss of it during the backoff fails at once, with the
 * failure it had and no request, however many readers and side-loads ask. The backoff starts at
 * {@link #FIRST_BACKOFF}, doubles with each failure in a row up to {@link #LONGEST_BACKOFF}, ends
 * with a success, and is counted on the feed clock's monotonic side. It is {@linkplain #forget
 * forgotten} when what is cached of the key is cleared. A failure nobody tried again after is
 * forgotten itself, and at most {@link #MAXIMUM_FAILURES} are remembered.
 *
 * <p>The fetch writes what it gets into the caches itself, and returns what the callers need. Since
 * nothing waits without a deadline, loads that wait for each other - a match for its competitors, a
 * competitor for its match - end in a timeout at worst, never in a deadlock; a load inside a fetch
 * waits no longer than that fetch's own deadline.
 *
 * <p>Safe for concurrent use.
 */
public final class Loader<K, V> {

    /** Fetches one key, and writes what it gets with a stamp that knows when it was abandoned. */
    @FunctionalInterface
    public interface Fetch<K, V> {
        /**
         * Fetches the key and writes what it gets into the caches.
         *
         * @param deadline the fetch's one deadline: every REST call it makes is given it, and so is
         *     every load it waits for
         * @param abandoned true once the fetch is past its callers' time, or was replaced: from then
         *     on its result must not be written, since a newer fetch of the key may have written
         *     already
         */
        V fetch(K key, Deadline deadline, BooleanSupplier abandoned);
    }

    /** How long a failed fetch backs its key off at first; it doubles with each failure in a row, as the catalogs'. */
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    /** The longest a failed fetch backs its key off, as the catalogs'. */
    static final Duration LONGEST_BACKOFF = Duration.ofMinutes(1);

    /** How many keys' last failures are remembered at most; one nobody asked for again goes sooner. */
    static final int MAXIMUM_FAILURES = 4_096;

    private final String name;
    private final Fetch<K, V> fetch;
    private final Duration deadline;
    private final Duration margin;
    private final Executor fetches;
    private final FeedClock clock;
    private final ConcurrentHashMap<K, Flight<V>> inFlight = new ConcurrentHashMap<>();
    /** The last failure of each key whose last fetch failed. */
    private final Cache<K, Failure> failures;
    /** A test's hook: told the key once a caller has joined or started its flight. */
    volatile Consumer<K> joined = _ -> {};
    /** A test's hook: told the key once a caller has found it not backing off, before it joins or starts a flight. */
    volatile Consumer<K> checked = _ -> {};

    /**
     * @param fetch fetches one key, within the deadline it is given
     * @param deadline the fetch's deadline, the HTTP client timeout
     * @param margin how much longer than the deadline a caller waits
     * @param fetches where the fetches run: virtual threads
     * @param clock the feed's clock: every deadline and every wait here is counted on its monotonic
     *     side, so a step of the wall clock cannot cut a fetch short or let it run on
     */
    public Loader(
            String name, Fetch<K, V> fetch, Duration deadline, Duration margin, Executor fetches, FeedClock clock) {
        this.clock = clock;
        this.name = name;
        this.fetch = fetch;
        this.deadline = deadline;
        this.margin = margin;
        this.fetches = fetches;
        Ticker ticker = clock::monoNanos;
        this.failures = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_FAILURES)
                // a failure outlives its backoff, so one that fails again goes on doubling; one nobody
                // tried again for that long starts over
                .expireAfterWrite(LONGEST_BACKOFF.multipliedBy(2))
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    /**
     * The key's value from its fetch, joining one already running.
     *
     * @throws ApiException when the fetch fails, does not finish in time, or the key backs off from
     *     its last failure
     */
    public V load(K key) {
        return load(key, null);
    }

    /**
     * The same, from inside another fetch: waiting no longer than {@code within}, that fetch's own
     * deadline, so the loads it waits for cannot keep it past it.
     *
     * @throws ApiException when the fetch fails, does not finish in time, or the key backs off from
     *     its last failure
     */
    public V load(K key, @Nullable Deadline within) {
        Failure last = backingOff(key);
        if (last != null) {
            throw backingOff(key, last);
        }
        checked.accept(key);
        var mine = new Flight<V>(Deadline.in(deadline, clock::monoNanos), margin);
        // a flight past its callers' time is abandoned and replaced, in the same step as callers join
        Flight<V> flight = inFlight.compute(key, (k, running) -> {
            if (running == null) {
                return mine;
            }
            if (running.expired(clock)) {
                running.abandoned().set(true);
                return mine;
            }
            return running;
        });
        joined.accept(key);
        if (flight.equals(mine)) {
            try {
                // in the map before it starts, so it cannot finish and be removed before it is there
                fetches.execute(() -> fetch(key, mine));
            } catch (RejectedExecutionException e) {
                var failure = new ApiException(name + " " + key + ": no fetch could start", null, e);
                inFlight.remove(key, mine);
                mine.result().completeExceptionally(failure);
                throw failure;
            }
        }
        // what is left of the caller's deadline, whatever clock that deadline counts on, taken on this one
        long until = within == null
                ? flight.expiresAt()
                : earlier(
                        flight.expiresAt(),
                        clock.monoNanos() + within.remaining().toNanos());
        try {
            // the flight's own deadline, not a fresh one per caller
            return flight.result().get(Math.max(0, until - clock.monoNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            String limit = until == flight.expiresAt()
                    ? deadline.plus(margin).toMillis() + " ms of its fetch starting"
                    : "its caller's deadline";
            throw new ApiException(name + " " + key + " not loaded within " + limit, null, e);
        } catch (ExecutionException e) {
            throw rethrown(key, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(name + " " + key + ": interrupted", null, e);
        }
    }

    /** How many keys are being fetched now, those abandoned but not yet replaced included. */
    public int inFlight() {
        return inFlight.size();
    }

    /**
     * The keys being fetched now, those abandoned but not yet replaced included. A key is here from
     * the moment its first caller asks, before its fetch runs, until that fetch has returned.
     */
    public Set<K> keys() {
        return Collections.unmodifiableSet(inFlight.keySet());
    }

    /**
     * Drops the backoff of the keys {@code which} accepts, so the next read of each fetches, and a
     * failure of a fetch already running for one of them backs nothing off. What a clear of a key's
     * cache does: it asked for a fresh load.
     */
    public void forget(Predicate<? super K> which) {
        // marked before the failures go, so a fetch that fails meanwhile records nothing
        inFlight.forEach((key, flight) -> {
            if (which.test(key)) {
                flight.forgotten().set(true);
            }
        });
        failures.asMap().keySet().removeIf(which);
    }

    /** Drops the backoff of every key. */
    public void forgetAll() {
        forget(_ -> true);
    }

    /** How many keys' last failures are remembered; for a test. */
    long failing() {
        failures.cleanUp();
        return failures.estimatedSize();
    }

    private void fetch(K key, Flight<V> flight) {
        // a reader that checked before can be late: a flight that failed in the meantime has recorded
        // its failure before it left, so the key is looked at again as the flight starts
        Failure last = backingOff(key);
        if (last != null) {
            inFlight.remove(key, flight);
            flight.result().completeExceptionally(backingOff(key, last));
            return;
        }
        V value;
        try {
            // abandoned once past its callers' time, replaced or not: no fetch outlives that, which an
            // entity cache remembering invalidations for that long relies on
            value = fetch.fetch(key, flight.deadline(), () -> flight.abandoned().get() || flight.expired(clock));
        } catch (RuntimeException | Error e) {
            if (e instanceof RuntimeException failure) {
                failed(key, flight, failure);
            }
            inFlight.remove(key, flight);
            flight.result().completeExceptionally(e);
            return;
        }
        // an abandoned fetch's success says nothing of the key now: a newer fetch may have failed since
        if (!flight.abandoned().get()) {
            failures.invalidate(key);
        }
        inFlight.remove(key, flight);
        flight.result().complete(value);
    }

    /**
     * Remembers the failure and backs the key off, unless the fetch was abandoned - a newer one owns
     * the key - or the key was forgotten while it ran. Before the flight leaves, so a reader that
     * finds no flight finds the failure.
     */
    private void failed(K key, Flight<V> flight, RuntimeException cause) {
        long at = clock.monoNanos();
        var wallAt = Instant.ofEpochMilli(clock.wallMillis());
        failures.asMap().compute(key, (k, last) -> {
            if (flight.abandoned().get()
                    || flight.expired(clock)
                    || flight.forgotten().get()) {
                return last;
            }
            return new Failure(at, wallAt, last == null ? 1 : last.attempts() + 1, cause);
        });
    }

    /** The key's last failure while it still backs the key off, else null. */
    private @Nullable Failure backingOff(K key) {
        Failure failure = failures.getIfPresent(key);
        return failure != null && failure.retryAt() - clock.monoNanos() > 0 ? failure : null;
    }

    private ApiException backingOff(K key, Failure failure) {
        return new ApiException(
                name + " " + key + ": backing off, not fetched again before " + failure.retryAtWall() + ", "
                        + failure.attempts() + " fetches in a row failed, the last at " + failure.wallAt(),
                null,
                failure.cause());
    }

    private static long earlier(long a, long b) {
        return a - b <= 0 ? a : b;
    }

    /** The fetch's own failure, as it was: an Error stays an Error, a runtime exception itself. */
    private RuntimeException rethrown(K key, Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException failure) {
            return failure;
        }
        Exception wrapped = cause instanceof Exception exception ? exception : new Exception(cause);
        return new ApiException(name + " " + key + " failed to load: " + cause, null, wrapped);
    }

    /**
     * One fetch: its result, whether it was abandoned, whether its key was forgotten while it ran,
     * its deadline, and until when its callers wait for it, on the feed's monotonic clock.
     */
    private record Flight<V>(
            CompletableFuture<V> result,
            AtomicBoolean abandoned,
            AtomicBoolean forgotten,
            Deadline deadline,
            long expiresAt) {
        Flight(Deadline deadline, Duration margin) {
            this(
                    new CompletableFuture<>(),
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    deadline,
                    deadline.endNanos() + margin.toNanos());
        }

        boolean expired(FeedClock clock) {
            return clock.monoNanos() - expiresAt > 0;
        }
    }

    /**
     * A key's last failed fetch - when it failed on the feed's monotonic clock, which the backoff
     * counts from, and by the wall clock, which only the message of a read it refuses tells -, how
     * many failed in a row, and why.
     */
    private record Failure(long at, Instant wallAt, int attempts, RuntimeException cause) {
        private long backoff() {
            return Math.min(FIRST_BACKOFF.toNanos() << Math.min(attempts - 1, 30), LONGEST_BACKOFF.toNanos());
        }

        /** When the next fetch may start, on the monotonic clock. */
        long retryAt() {
            return at + backoff();
        }

        /** The same as the wall clock told it, for a message. */
        Instant retryAtWall() {
            return wallAt.plusNanos(backoff());
        }
    }
}
