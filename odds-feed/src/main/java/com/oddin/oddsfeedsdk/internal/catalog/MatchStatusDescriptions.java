package com.oddin.oddsfeedsdk.internal.catalog;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchStatusDescription;
import java.time.Duration;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * What each match status id means, per locale: each locale's list fetched whole, refreshed after
 * {@link #REFRESH_AGE} and served while a refresh runs or fails, up to {@link #MAX_STALENESS}; see
 * {@link Catalog}. A refresh replaces the locale's list; an id missing from it refreshes the list
 * once in the background.
 *
 * <p>Safe for concurrent use.
 */
public final class MatchStatusDescriptions {

    /** How old a locale's list gets before a read refreshes it. */
    static final Duration REFRESH_AGE = Duration.ofHours(1);

    /** How old a locale's list gets before it is no longer served. */
    static final Duration MAX_STALENESS = Duration.ofHours(24);

    /** How many locales' lists are kept. */
    static final long LOCALES = 100;

    private final ApiClient client;
    private final Catalog<Locale, Map<Long, String>> lists;

    /**
     * @param timeout the HTTP client timeout, each fetch's deadline
     * @param fetches where the fetches run: virtual threads
     */
    public MatchStatusDescriptions(ApiClient client, Duration timeout, Executor fetches) {
        this(client, timeout, fetches, fetches, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives, and where it runs the background refreshes. */
    MatchStatusDescriptions(
            ApiClient client,
            Duration timeout,
            Executor fetches,
            Executor refreshes,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.lists = new Catalog<>(
                "match status descriptions",
                LOCALES,
                REFRESH_AGE,
                MAX_STALENESS,
                this::fetchList,
                timeout,
                fetches,
                refreshes,
                clock,
                ticker);
    }

    /**
     * What the match status {@code id} means in {@code locale}, or null when the list does not say.
     *
     * @throws ApiException when the list is not held and cannot be fetched
     */
    public @Nullable String description(long id, Locale locale) {
        return lists.find(locale, id, Map::get);
    }

    /** Drops every locale's list. */
    public void clear() {
        lists.clear();
    }

    public CatalogHealth health() {
        return lists.health();
    }

    /** A locale's list; an empty one does not replace one with statuses in it, and counts as failed. */
    private Map<Long, String> fetchList(Locale locale, @Nullable Map<Long, String> previous, Deadline deadline) {
        var descriptions = new HashMap<Long, String>();
        for (RAMatchStatusDescription status :
                client.fetchMatchStatusDescriptions(locale, deadline).getMatchStatus()) {
            @Nullable String description = status.getDescription();
            if (description != null) {
                descriptions.put(status.getId(), description);
            }
        }
        if (descriptions.isEmpty() && previous != null && !previous.isEmpty()) {
            throw new ApiException(
                    "match status descriptions in " + locale + ": an empty list does not replace one of "
                            + previous.size(),
                    null,
                    null);
        }
        return Map.copyOf(descriptions);
    }
}
