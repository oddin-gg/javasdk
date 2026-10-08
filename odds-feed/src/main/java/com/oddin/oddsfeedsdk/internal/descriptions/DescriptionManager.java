package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.catalog.VoidReasons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * The client's {@link MarketDescriptionManager}, over the market description and void reason
 * catalogs, answering an outage as 0.0.x did: the market descriptions are an empty list and a market
 * description is null under either strategy, while the void reasons fail under {@code THROW}, with
 * the API's own exception, and are null under {@code CATCH}. A market the catalog does not have is
 * null under either, as in 0.0.x; a list is never one with only part of what it should have, and
 * always a new one, the caller's own.
 *
 * <p>Safe for concurrent use.
 */
public final class DescriptionManager implements MarketDescriptionManager {

    private final MarketDescriptions markets;
    private final VoidReasons voidReasons;
    private final Locale defaultLocale;
    private final Strategy strategy;
    private final MarketReads reads;

    /** @param fetches where the locales of a description in several load, in parallel: virtual threads */
    public DescriptionManager(
            MarketDescriptions markets,
            VoidReasons voidReasons,
            Locale defaultLocale,
            ExceptionHandlingStrategy strategy,
            Executor fetches) {
        this.markets = markets;
        this.voidReasons = voidReasons;
        this.defaultLocale = defaultLocale;
        this.strategy = new Strategy(strategy);
        this.reads = new MarketReads(markets, this.strategy, fetches);
    }

    /** Every market description in the default locale. */
    @Override
    public @Nullable List<MarketDescription> getMarketDescriptions() {
        return getMarketDescriptions(defaultLocale);
    }

    /**
     * Every market description in {@code locale}: the locale's list, and the dynamic-outcome variants
     * held in it. Each reads as the listing does: a row the list has asks no variant's endpoint. A new
     * list each time, as 0.0.x gave; an empty one when the list cannot be loaded, under either
     * strategy, as 0.0.x gave too.
     */
    @Override
    public @Nullable List<MarketDescription> getMarketDescriptions(Locale locale) {
        List<LocalizedMarket> described =
                Strategy.quietly(() -> markets.markets(locale), "market descriptions in", locale);
        var in = List.of(locale);
        var all = new ArrayList<MarketDescription>(described == null ? 0 : described.size());
        for (LocalizedMarket market : described == null ? List.<LocalizedMarket>of() : described) {
            all.add(new MarketDescriptionView(reads, market, in, true));
        }
        return all;
    }

    /**
     * The market's description in {@code locale}; null when the catalog does not have it, or cannot
     * be loaded, under either strategy, as in 0.0.x.
     */
    @Override
    public @Nullable MarketDescription getMarketDescription(int marketId, @Nullable String variant, Locale locale) {
        return getMarketDescription(marketId, variant, List.of(locale));
    }

    /**
     * The market's description in {@code locales}, each loaded, the ones not held in parallel; null
     * when none of them has it, or one cannot be loaded, under either strategy, as in 0.0.x. For the
     * markets of a message, in the locales the client wants.
     *
     * @param locales none for the default locale
     */
    public @Nullable MarketDescription getMarketDescription(
            int marketId, @Nullable String variant, List<Locale> locales) {
        List<Locale> in = locales.isEmpty() ? List.of(defaultLocale) : List.copyOf(locales);
        return Strategy.quietly(
                () -> {
                    LocalizedMarket described = reads.first(marketId, variant, in, false);
                    return described == null ? null : new MarketDescriptionView(reads, described, in, false);
                },
                "market description",
                marketId + (variant == null ? "" : " " + variant));
    }

    /**
     * Drops the market's description in every locale, so the next read fetches it again: a
     * dynamic-outcome variant by itself, a listed market with the lists it is in.
     */
    @Override
    public void clearMarketDescription(int marketId, @Nullable String variant) {
        markets.clear(marketId, variant);
    }

    /** Every void reason, by id. A new list each time, as 0.0.x gave; the API's failure as it is. */
    @Override
    public @Nullable List<MarketVoidReason> getMarketVoidReasons() {
        return strategy.call(() -> new ArrayList<MarketVoidReason>(voidReasons.all()), "void reasons", "");
    }

    /** Drops the void reasons, so the next read fetches them again. */
    @Override
    public void clearMarketVoidReasons() {
        voidReasons.clear();
    }

    /** The void reasons fetched now, whatever is held; what was held stays when the fetch fails. */
    @Override
    public @Nullable List<MarketVoidReason> reloadMarketVoidReasons() {
        return strategy.call(() -> new ArrayList<MarketVoidReason>(voidReasons.reload()), "void reasons", "reloaded");
    }
}
