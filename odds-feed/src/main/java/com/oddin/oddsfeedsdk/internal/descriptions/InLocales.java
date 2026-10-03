package com.oddin.oddsfeedsdk.internal.descriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
                R found = joined(load);
                first = first == null ? found : first;
            } else if (first == null) {
                first = read.apply(locales.get(i));
            }
        }
        return first;
    }

    /** What a load got, or its own failure as it was thrown. */
    private static <R> @Nullable R joined(CompletableFuture<? extends @Nullable R> load) {
        try {
            return load.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (wrapped.getCause() instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }
}
