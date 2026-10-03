package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Loads side by side, at most {@code limit} at once: a match's locales, a competitor's players. A
 * load starts only when one before it has finished, so each load's deadline starts when the load
 * does, and a long list cannot spend its last loads' time waiting for a permit. The loads go through
 * the loaders, so two lists that share an entity share its load.
 *
 * <p>All of them or none: the first load that fails fails the whole, at once, and no later load is
 * started. A load already running finishes on its own and writes what it got, for the next reader.
 *
 * <p>Safe for concurrent use.
 */
final class FanOut {

    private final Executor threads;
    private final int limit;
    private final Duration longestLoad;

    /**
     * @param threads where the loads wait: virtual threads, so that a load that fans out itself
     *     never waits for a thread
     * @param limit how many loads of one fan-out run at once
     * @param longestLoad how long one load can take, its loader's deadline and margin included: what
     *     a wait for a permit, or for the last loads, is bounded by
     */
    FanOut(Executor threads, int limit, Duration longestLoad) {
        if (limit < 1) {
            throw new IllegalArgumentException("a fan-out runs one load at least");
        }
        this.threads = threads;
        this.limit = limit;
        this.longestLoad = longestLoad;
    }

    /**
     * What {@code load} returns for each item, in their order. A single item is loaded on the
     * caller's thread.
     *
     * @throws RuntimeException the first failure of a load, as it was thrown
     */
    <T, R> List<R> each(List<T> items, Function<? super T, ? extends R> load) {
        if (items.size() <= 1) {
            var results = new ArrayList<R>(items.size());
            for (T item : items) {
                results.add(load.apply(item));
            }
            return results;
        }
        var permits = new Semaphore(limit);
        var failed = new CompletableFuture<Void>();
        var running = new ArrayList<CompletableFuture<R>>(items.size());
        for (T item : items) {
            if (failed.isDone()) {
                break;
            }
            acquire(permits, failed);
            var result = new CompletableFuture<R>();
            try {
                threads.execute(() -> {
                    try {
                        result.complete(load.apply(item));
                    } catch (Throwable e) {
                        result.completeExceptionally(e);
                        failed.completeExceptionally(e);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                permits.release();
                throw new ApiException("no load could start", null, e);
            }
            running.add(result);
        }
        // the last loads started within the longest load: all of them done, or the first failure
        await(CompletableFuture.anyOf(CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)), failed));
        var results = new ArrayList<R>(running.size());
        for (CompletableFuture<R> result : running) {
            results.add(result.join());
        }
        return results;
    }

    /** A permit, unless a load failed meanwhile: then that failure. */
    private void acquire(Semaphore permits, CompletableFuture<Void> failed) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(longestLoad.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted while loading", null, e);
        }
        if (!acquired) {
            throw new ApiException("no load finished within " + longestLoad.toMillis() + " ms", null, null);
        }
        if (failed.isDone()) {
            permits.release();
            await(failed);
        }
    }

    private void await(CompletableFuture<?> done) {
        try {
            done.get(longestLoad.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw rethrown(e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            throw new ApiException("not loaded within " + longestLoad.toMillis() + " ms", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted while loading", null, e);
        }
    }

    /** A load's own failure, as it was: an Error stays an Error, a runtime exception itself. */
    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException failure) {
            return failure;
        }
        Exception wrapped = cause instanceof Exception exception ? exception : new Exception(cause);
        return new ApiException("failed to load: " + cause, null, wrapped);
    }
}
