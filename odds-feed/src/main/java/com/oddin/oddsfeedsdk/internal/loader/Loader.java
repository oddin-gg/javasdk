package com.oddin.oddsfeedsdk.internal.loader;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Single-flight fetching by key: concurrent misses for one key share one fetch, run off the
 * callers' threads. Every caller waits at most the fetch's own deadline plus a margin, and a caller
 * that runs out of time fails; it never starts a fetch of its own. A fetch that fails fails every
 * caller waiting for it, and the next miss starts afresh: a fetch leaves before it tells anyone how
 * it went.
 *
 * <p>The fetch writes what it gets into the caches itself, and returns what the callers need. Since
 * nothing waits without a deadline, loads that wait for each other - a match for its competitors, a
 * competitor for its match - end in a timeout at worst, never in a deadlock.
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
         * @param abandoned true once every caller gave up on this fetch: from then on its result must
         *     not be written, since a newer fetch of the key may have written already
         */
        V fetch(K key, BooleanSupplier abandoned);
    }

    private final String name;
    private final Fetch<K, V> fetch;
    private final Duration wait;
    private final Executor fetches;
    private final ConcurrentHashMap<K, Flight<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * @param fetch fetches one key, bounded by its own deadline
     * @param deadline the fetch's deadline, the HTTP client timeout
     * @param margin how much longer than the deadline a caller waits
     * @param fetches where the fetches run: virtual threads
     */
    public Loader(String name, Fetch<K, V> fetch, Duration deadline, Duration margin, Executor fetches) {
        this.name = name;
        this.fetch = fetch;
        this.wait = deadline.plus(margin);
        this.fetches = fetches;
    }

    /**
     * The key's value from its fetch, joining one already running.
     *
     * @throws ApiException when the fetch fails, or does not finish in time
     */
    public V load(K key) {
        var mine = new Flight<V>(System.nanoTime() + wait.toNanos());
        // joined and counted in one step, so the last to give up cannot miss a caller joining; a
        // flight past its deadline is abandoned and replaced, however many still wait for it
        Flight<V> flight = inFlight.compute(key, (k, running) -> {
            Flight<V> joined;
            if (running == null) {
                joined = mine;
            } else if (running.expired()) {
                running.abandoned().set(true);
                joined = mine;
            } else {
                joined = running;
            }
            joined.waiters().incrementAndGet();
            return joined;
        });
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
        boolean overran = false;
        try {
            // the flight's own deadline, not a fresh one per caller
            return flight.result().get(Math.max(0, flight.expiresAt() - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            overran = true;
            throw new ApiException(name + " " + key + " not loaded within " + wait.toMillis() + " ms", null, e);
        } catch (ExecutionException e) {
            throw rethrown(key, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(name + " " + key + ": interrupted", null, e);
        } finally {
            leave(key, flight, overran);
        }
    }

    /**
     * A caller stops waiting. The last of those that ran out of time lets go of the fetch that overran,
     * so the next miss starts afresh; counted down in the same step as callers join, so two that give
     * up together cannot both see the other still waiting.
     */
    private void leave(K key, Flight<V> flight, boolean overran) {
        inFlight.compute(key, (k, current) -> {
            int left = flight.waiters().decrementAndGet();
            if (overran && left == 0) {
                // abandoned before it leaves the map, so no newer fetch can write before it is
                flight.abandoned().set(true);
                return flight.equals(current) ? null : current;
            }
            return current;
        });
    }

    /** How many keys are being fetched now. */
    public int inFlight() {
        return inFlight.size();
    }

    /** How many callers wait for the key's fetch now. */
    int waiting(K key) {
        Flight<V> flight = inFlight.get(key);
        return flight == null ? 0 : flight.waiters().get();
    }

    private void fetch(K key, Flight<V> flight) {
        V value;
        try {
            value = fetch.fetch(key, flight.abandoned()::get);
        } catch (RuntimeException | Error e) {
            inFlight.remove(key, flight);
            flight.result().completeExceptionally(e);
            return;
        }
        inFlight.remove(key, flight);
        flight.result().complete(value);
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

    /** One fetch: its result, how many callers wait for it, whether it was abandoned, its deadline. */
    private record Flight<V>(
            CompletableFuture<V> result, AtomicInteger waiters, AtomicBoolean abandoned, long expiresAt) {
        Flight(long expiresAt) {
            this(new CompletableFuture<>(), new AtomicInteger(), new AtomicBoolean(), expiresAt);
        }

        boolean expired() {
            return System.nanoTime() - expiresAt > 0;
        }
    }
}
