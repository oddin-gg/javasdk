package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
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
    private final Strategy strategy;
    private final Executor fetches;

    /** @param fetches where the locales not held load, in parallel: virtual threads */
    public StatusDescriptions(MatchStatusDescriptions catalog, ExceptionHandlingStrategy strategy, Executor fetches) {
        this.catalog = catalog;
        this.strategy = new Strategy(strategy);
        this.fetches = fetches;
    }

    /**
     * The match status {@code id} in {@code locales}, each loaded, the ones not held in parallel; null
     * when none of them describes it, as in 0.0.x. A locale that cannot be fetched fails it under
     * {@code THROW} and makes it null under {@code CATCH}.
     *
     * @param locales not empty
     */
    public @Nullable LocalizedStaticData get(long id, List<Locale> locales) {
        if (locales.isEmpty()) {
            throw new IllegalArgumentException("match status " + id + " asked for in no locale");
        }
        return strategy.read(
                () -> {
                    String first = InLocales.first(
                            locales, catalog::holds, locale -> catalog.description(id, locale), fetches);
                    return first == null ? null : new MatchStatusView(id, first, this);
                },
                "match status description",
                id);
    }

    /** What the match status {@code id} means in {@code locale}, failing as the strategy says. */
    @Nullable
    String description(long id, Locale locale) {
        return strategy.read(() -> catalog.description(id, locale), "match status description", id);
    }
}
