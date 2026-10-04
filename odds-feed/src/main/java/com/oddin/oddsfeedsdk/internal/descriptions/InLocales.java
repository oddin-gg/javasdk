package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** One item read in several locales, the locales not held loading in parallel. */
final class InLocales {

    private InLocales() {}

    /**
     * The item in the first of {@code locales} that has it, null when none has it. The locales not
     * held load all at once, each on a thread of its own, and all of them, so that the item is
     * held in every locale afterwards; the held ones are read on the caller's thread, which then
     * waits for nothing. When a locale fails, the read fails with the failure of the first locale
     * that failed, in their order: never an answer from part of the locales.
     *
     * @param held whether a locale is held, so that reading it waits for no fetch
     * @param fetches where the locales not held load: virtual threads
     */
    static <R> @Nullable R first(
            List<Locale> locales,
            Predicate<Locale> held,
            Function<Locale, ? extends @Nullable R> read,
            Executor fetches) {
        if (locales.size() == 1) {
            return read.apply(locales.getFirst());
        }
        var loads = new ArrayList<@Nullable CompletableFuture<? extends @Nullable R>>(locales.size());
        for (Locale locale : locales) {
            loads.add(held.test(locale) ? null : CompletableFuture.supplyAsync(() -> read.apply(locale), fetches));
        }
        R first = null;
        for (int i = 0; i < locales.size(); i++) {
            CompletableFuture<? extends @Nullable R> load = loads.get(i);
            if (load != null) {
                R found = joined(load, loads, locales);
                first = first == null ? found : first;
            } else if (first == null) {
                first = read.apply(locales.get(i));
            }
        }
        return first;
    }

    /**
     * What a load got, or its own failure as it was thrown. An interrupted caller stops waiting at
     * once, as a read in one locale does: the interrupt is kept, the loads not done are cancelled,
     * and the read fails.
     */
    private static <R> @Nullable R joined(
            CompletableFuture<? extends @Nullable R> load,
            List<@Nullable CompletableFuture<? extends @Nullable R>> loads,
            List<Locale> locales) {
        try {
            return load.get();
        } catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (wrapped.getCause() instanceof Error error) {
                throw error;
            }
            throw new ApiException("read in " + locales + " failed: " + wrapped.getCause(), null, wrapped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (CompletableFuture<? extends @Nullable R> pending : loads) {
                if (pending != null) {
                    pending.cancel(true);
                }
            }
            throw new ApiException("read in " + locales + ": interrupted", null, e);
        }
    }
}
