package com.oddin.oddsfeedsdk.internal.catalog;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.internal.rest.HttpStatusException;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescription;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * The market descriptions, kept apart by where they came from.
 *
 * <ul>
 *   <li>Listed: each locale's list, fetched whole from the list endpoint. A refresh replaces the
 *       locale's list, so a market removed upstream is gone after it; a market missing from a list
 *       fetches the list again once, and the read waits for it, so one added upstream is found by
 *       the read that first asks for it; while the list's last fetch failed, it is refreshed in the
 *       background instead.
 *   <li>Fetched on their own: the dynamic-outcome variants, one per market, variant and locale, from
 *       the variant's endpoint. A list refresh leaves them alone; each refreshes on its own age.
 * </ul>
 *
 * <p>Both refresh after {@link #REFRESH_AGE} and serve what they hold while a refresh runs and for as
 * long as refreshes fail; see {@link Catalog}.
 *
 * <p>Safe for concurrent use.
 */
public final class MarketDescriptions {

    /** How old a list or a variant gets before a read refreshes it. */
    static final Duration REFRESH_AGE = Duration.ofHours(1);

    /** How many locales' lists are kept. */
    static final long LOCALES = 100;

    /** How many variants are kept, over all locales: the long tail of dynamic outcomes. */
    static final long VARIANTS = 5_000;

    private final ApiClient client;
    private final Catalog<Locale, MarketList> lists;
    private final Catalog<VariantKey, LocalizedMarket> variants;

    /**
     * @param timeout the HTTP client timeout, each fetch's deadline
     * @param fetches where the fetches run: virtual threads
     */
    public MarketDescriptions(ApiClient client, Duration timeout, Executor fetches) {
        this(client, timeout, fetches, fetches, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives, and where it runs the background refreshes. */
    MarketDescriptions(
            ApiClient client,
            Duration timeout,
            Executor fetches,
            Executor refreshes,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.lists = new Catalog<>(
                "market descriptions",
                LOCALES,
                REFRESH_AGE,
                this::fetchList,
                timeout,
                fetches,
                refreshes,
                clock,
                ticker,
                client::isClosed);
        this.variants = new Catalog<>(
                "market variants",
                VARIANTS,
                REFRESH_AGE,
                this::fetchVariant,
                timeout,
                fetches,
                refreshes,
                clock,
                ticker,
                client::isClosed);
    }

    /**
     * The market's description in {@code locale}: a dynamic-outcome variant's own, else the one the
     * locale's list has. Null when the list does not have it, or the variant's endpoint does not know
     * the variant.
     *
     * @throws ApiException when it is not held and cannot be fetched
     */
    public @Nullable LocalizedMarket market(int id, @Nullable String variant, Locale locale) {
        var key = MarketKey.of(id, variant);
        if (!key.isDynamic()) {
            return lists.find(locale, key, MarketList::get);
        }
        try {
            return variants.get(new VariantKey(key, locale));
        } catch (ApiException failed) {
            if (HttpStatusException.statusOf(failed) == 404) {
                return null;
            }
            throw failed;
        }
    }

    /**
     * Whether what the market's description in {@code locale} is read from is held, however old: a
     * read of it then waits for no fetch, unless the market is missing from the list.
     */
    public boolean holds(int id, @Nullable String variant, Locale locale) {
        var key = MarketKey.of(id, variant);
        return key.isDynamic() ? variants.peek(new VariantKey(key, locale)) != null : lists.peek(locale) != null;
    }

    /**
     * The market's description in {@code locale} as {@link #markets} lists it: a dynamic-outcome
     * variant fetched on its own when one is held, else the locale's list row, so that reading what
     * the listing gave asks no variant's endpoint for a row the list has. A dynamic variant the list
     * has no row for is read from its own endpoint, as {@link #market} reads it. Null when it is not
     * there.
     *
     * @throws ApiException when what it is read from is not held and cannot be fetched
     */
    public @Nullable LocalizedMarket listed(int id, @Nullable String variant, Locale locale) {
        var key = MarketKey.of(id, variant);
        if (!key.isDynamic()) {
            return lists.find(locale, key, MarketList::get);
        }
        // served as a read by id serves it: refreshed when old, and counted stale
        LocalizedMarket own = variants.getHeld(new VariantKey(key, locale));
        if (own != null) {
            return own;
        }
        // a variant missing from the list is no market new upstream: the list is not fetched again
        LocalizedMarket row = lists.get(locale).get(key);
        return row != null ? row : market(id, variant, locale);
    }

    /**
     * Whether what {@link #listed} reads the market's description in {@code locale} from is held,
     * however old: a read of it then waits for no fetch, unless a listed market is missing from the
     * list.
     */
    public boolean holdsListed(int id, @Nullable String variant, Locale locale) {
        var key = MarketKey.of(id, variant);
        MarketList list = lists.peek(locale);
        if (!key.isDynamic()) {
            return list != null;
        }
        return variants.peek(new VariantKey(key, locale)) != null || (list != null && list.get(key) != null);
    }

    /**
     * Every market description in {@code locale}: the locale's list, in its order, and the variants
     * fetched on their own in that locale, as 0.0.x listed what it held.
     *
     * @throws ApiException when the list is not held and cannot be fetched
     */
    public List<LocalizedMarket> markets(Locale locale) {
        var all = new LinkedHashMap<>(lists.get(locale).byKey());
        variants.peekAll().forEach((key, market) -> {
            if (key.locale().equals(locale)) {
                all.put(key.market(), market);
            }
        });
        return List.copyOf(all.values());
    }

    /**
     * Fetches the market descriptions of {@code locale} unless they are held, for no reader: a failure
     * backs off no read.
     *
     * @throws ApiException when the fetch fails
     */
    public void preload(Locale locale) {
        lists.preload(locale);
    }

    /**
     * Drops the market's description in every locale, so the next read fetches it again: a variant
     * fetched on its own by itself, a listed market with every list it is in.
     */
    public void clear(int id, @Nullable String variant) {
        var key = MarketKey.of(id, variant);
        if (key.isDynamic()) {
            variants.clear(held -> held.market().equals(key));
        } else {
            lists.clear();
        }
    }

    /** Drops every market description. */
    public void clear() {
        lists.clear();
        variants.clear();
    }

    /** The lists' health and the variants'. */
    public List<CatalogHealth> health() {
        return List.of(lists.health(), variants.health());
    }

    /** A locale's list; an empty one does not replace one with markets in it, and counts as failed. */
    private MarketList fetchList(Locale locale, @Nullable MarketList previous, Deadline deadline) {
        var list =
                MarketList.of(client.fetchMarketDescriptions(locale, deadline).getMarket());
        if (list.size() == 0 && previous != null && previous.size() > 0) {
            throw new ApiException(
                    "market descriptions in " + locale + ": an empty list does not replace one of " + previous.size()
                            + " markets",
                    null,
                    null);
        }
        return list;
    }

    /**
     * One variant: of the rows of the variant's market in the answer, the one with the variant, else
     * one with no variant. A row of another variant describes other outcomes, such as another
     * match's players, so an answer with only those does not describe the variant.
     */
    private LocalizedMarket fetchVariant(VariantKey key, @Nullable LocalizedMarket previous, Deadline deadline) {
        int id = key.market().id();
        String variant = requireNonNull(key.market().variant());
        var answer = client.fetchMarketDescriptionsWithDynamicOutcomes(id, variant, key.locale(), deadline);
        RAMarketDescription described = null;
        for (RAMarketDescription market : answer.getMarket()) {
            if (market.getId() != id) {
                continue;
            }
            @Nullable String rowVariant = MarketKey.of(id, market.getVariant()).variant();
            if (variant.equals(rowVariant)) {
                described = market;
                break;
            }
            if (rowVariant == null && described == null) {
                described = market;
            }
        }
        if (described == null) {
            throw new ApiException(
                    "market descriptions: the answer for " + key + " does not describe market " + id
                            + " with that variant or none",
                    null,
                    null);
        }
        return LocalizedMarket.from(described, variant);
    }

    /** A variant in a locale: what a variant is fetched for. */
    record VariantKey(MarketKey market, Locale locale) {
        @Override
        public String toString() {
            return market + " in " + locale;
        }
    }
}
