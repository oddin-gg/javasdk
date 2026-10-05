package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import com.oddin.oddsfeedsdk.internal.catalog.MatchStatusDescriptions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * What the match status ids mean, as a match's status gives them to the client: {@link
 * LocalizedStaticData} over the match status description catalog.
 *
 * <p>Safe for concurrent use.
 */
public final class StatusDescriptions {

    private final MatchStatusDescriptions catalog;
    private final Executor fetches;

    /** @param fetches where the locales not held load, in parallel: virtual threads */
    public StatusDescriptions(MatchStatusDescriptions catalog, Executor fetches) {
        this.catalog = catalog;
        this.fetches = fetches;
    }

    /**
     * The match status {@code id} in {@code locales}, each loaded, the ones not held in parallel; null
     * when none of them describes it, or one cannot be fetched, under either strategy, as in 0.0.x,
     * which read it from what it held and never failed.
     *
     * @param locales not empty
     */
    public @Nullable LocalizedStaticData get(long id, List<Locale> locales) {
        if (locales.isEmpty()) {
            throw new IllegalArgumentException("match status " + id + " asked for in no locale");
        }
        return Strategy.quietly(
                () -> {
                    String first = InLocales.first(
                            locales, catalog::holds, locale -> catalog.description(id, locale), fetches);
                    return first == null ? null : new MatchStatusView(id, first, this);
                },
                "match status description",
                id);
    }

    /** What the match status {@code id} means in {@code locale}; null when it cannot be fetched, as in 0.0.x. */
    @Nullable
    String description(long id, Locale locale) {
        return Strategy.quietly(() -> catalog.description(id, locale), "match status description", id);
    }
}
