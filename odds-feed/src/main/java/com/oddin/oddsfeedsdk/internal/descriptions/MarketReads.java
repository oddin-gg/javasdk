package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * How the market description views read: through the catalog, failing as the strategy says, the
 * locales not held loading in parallel.
 *
 * @param fetches where the locales not held load: virtual threads
 */
record MarketReads(MarketDescriptions catalog, Strategy strategy, Executor fetches) {

    /**
     * The market's description in the first of {@code locales} that has it, every locale loaded;
     * null when none has it.
     *
     * @param listed whether to read it as the listing gave it, see {@link MarketDescriptions#listed},
     *     rather than as a market got by id
     * @throws com.oddin.oddsfeedsdk.exceptions.ApiException when a locale is not held and cannot be
     *     fetched
     */
    @Nullable
    LocalizedMarket first(int id, @Nullable String variant, List<Locale> locales, boolean listed) {
        return InLocales.first(
                locales,
                listed
                        ? locale -> catalog.holdsListed(id, variant, locale)
                        : locale -> catalog.holds(id, variant, locale),
                locale -> in(id, variant, locale, listed),
                fetches);
    }

    /**
     * The market's description in {@code locale}, null when it is not there.
     *
     * @param listed whether to read it as the listing gave it, rather than as a market got by id
     */
    @Nullable
    LocalizedMarket in(int id, @Nullable String variant, Locale locale, boolean listed) {
        return listed ? catalog.listed(id, variant, locale) : catalog.market(id, variant, locale);
    }
}
