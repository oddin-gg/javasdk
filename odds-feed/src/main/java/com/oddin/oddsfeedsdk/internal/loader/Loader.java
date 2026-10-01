package com.oddin.oddsfeedsdk.internal.loader;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import java.time.Duration;
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
import org.jspecify.annotations.Nullable;

/**
 * Single-flight fetching by key: concurrent misses for one key share one fetch, run off the
 * callers' threads. A fetch has one deadline, the HTTP client timeout from when it starts, and every
 * REST call it makes draws from it; every caller waits at most until that deadline plus a margin,
 * however late it joined, and a caller that runs out of time fails - it never starts a fetch of its
 * own. A fetch that fails fails every caller waiting for it, and the next miss starts afresh: a
 * fetch leaves before it tells anyone how it went. One still running past its callers' time is
 * abandoned, writes nothing from then on, and is replaced by the next miss.
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

    private final String name;
    private final Fetch<K, V> fetch;
    private final Duration deadline;
    private final Duration margin;
    private final Executor fetches;
    private final ConcurrentHashMap<K, Flight<V>> inFlight = new ConcurrentHashMap<>();
    /** A test's hook: told the key once a caller has joined or started its flight. */
    volatile Consumer<K> joined = _ -> {};

    /**
     * @param fetch fetches one key, within the deadline it is given
     * @param deadline the fetch's deadline, the HTTP client timeout
     * @param margin how much longer than the deadline a caller waits
     * @param fetches where the fetches run: virtual threads
     */
    public Loader(String name, Fetch<K, V> fetch, Duration deadline, Duration margin, Executor fetches) {
        this.name = name;
        this.fetch = fetch;
        this.deadline = deadline;
        this.margin = margin;
        this.fetches = fetches;
    }

    /**
     * The key's value from its fetch, joining one already running.
     *
     * @throws ApiException when the fetch fails, or does not finish in time
     */
    public V load(K key) {
        return load(key, null);
    }

    /**
     * The same, from inside another fetch: waiting no longer than {@code within}, that fetch's own
     * deadline, so the loads it waits for cannot keep it past it.
     *
     * @throws ApiException when the fetch fails, or does not finish in time
     */
    public V load(K key, @Nullable Deadline within) {
        var mine = new Flight<V>(Deadline.in(deadline), margin);
        // a flight past its callers' time is abandoned and replaced, in the same step as callers join
        Flight<V> flight = inFlight.compute(key, (k, running) -> {
            if (running == null) {
                return mine;
            }
            if (running.expired()) {
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
        long until = within == null ? flight.expiresAt() : earlier(flight.expiresAt(), within.endNanos());
        try {
            // the flight's own deadline, not a fresh one per caller
            return flight.result().get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
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

    private void fetch(K key, Flight<V> flight) {
        V value;
        try {
            // abandoned once past its callers' time, replaced or not: no fetch outlives that, which an
            // entity cache remembering invalidations for that long relies on
            value = fetch.fetch(key, flight.deadline(), () -> flight.abandoned().get() || flight.expired());
        } catch (RuntimeException | Error e) {
            inFlight.remove(key, flight);
            flight.result().completeExceptionally(e);
            return;
        }
        inFlight.remove(key, flight);
        flight.result().complete(value);
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
     * One fetch: its result, whether it was abandoned, its deadline, and until when its callers wait
     * for it, on the {@link System#nanoTime()} clock.
     */
    private record Flight<V>(CompletableFuture<V> result, AtomicBoolean abandoned, Deadline deadline, long expiresAt) {
        Flight(Deadline deadline, Duration margin) {
            this(new CompletableFuture<>(), new AtomicBoolean(), deadline, deadline.endNanos() + margin.toNanos());
        }

        boolean expired() {
            return System.nanoTime() - expiresAt > 0;
        }
    }
}
