package com.oddin.oddsfeedsdk.internal.loader;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Single-flight fetching by key: concurrent misses for one key share one fetch, run off the
 * callers' threads. Every caller waits at most the fetch's own deadline plus a margin, and a caller
 * that runs out of time fails; it never starts a fetch of its own. A fetch that fails fails every
 * caller waiting for it, and the next miss starts afresh.
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
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

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
        var mine = new CompletableFuture<V>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        CompletableFuture<V> shared = running != null ? running : mine;
        if (running == null) {
            // in the map before it starts, so it cannot finish and be removed before it is there
            fetches.execute(() -> fetch(key, mine));
        }
        try {
            return shared.get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(name + " " + key + " not loaded within " + wait.toMillis() + " ms", null, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            throw new ApiException(name + " " + key + " failed to load: " + cause, null, (Exception) cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(name + " " + key + ": interrupted", null, e);
        }
    }

    /** How many keys are being fetched now. */
    public int inFlight() {
        return inFlight.size();
    }

    private void fetch(K key, CompletableFuture<V> result) {
        try {
            result.complete(fetch.apply(key));
        } catch (RuntimeException | Error e) {
            result.completeExceptionally(e);
        } finally {
            inFlight.remove(key, result);
        }
    }
}
