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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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

    private final String name;
    private final Function<K, V> fetch;
    private final Duration wait;
    private final Executor fetches;
    private final ConcurrentHashMap<K, Flight<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * @param fetch fetches one key, bounded by its own deadline
     * @param deadline the fetch's deadline, the HTTP client timeout
     * @param margin how much longer than the deadline a caller waits
     * @param fetches where the fetches run: virtual threads
     */
    public Loader(String name, Function<K, V> fetch, Duration deadline, Duration margin, Executor fetches) {
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
        var mine = new Flight<V>();
        Flight<V> running = inFlight.putIfAbsent(key, mine);
        Flight<V> flight = running != null ? running : mine;
        if (running == null) {
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
        flight.waiters().incrementAndGet();
        try {
            return flight.result().get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // the last to give up lets go of a fetch that overran, so the next miss starts afresh
            if (flight.waiters().get() == 1) {
                inFlight.remove(key, flight);
            }
            throw new ApiException(name + " " + key + " not loaded within " + wait.toMillis() + " ms", null, e);
        } catch (ExecutionException e) {
            throw rethrown(key, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(name + " " + key + ": interrupted", null, e);
        } finally {
            flight.waiters().decrementAndGet();
        }
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
            value = fetch.apply(key);
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

    /** One fetch: its result, and how many callers wait for it. */
    private record Flight<V>(CompletableFuture<V> result, AtomicInteger waiters) {
        Flight() {
            this(new CompletableFuture<>(), new AtomicInteger());
        }
    }
}
