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
     * @throws com.oddin.oddsfeedsdk.exceptions.ApiException when a locale is not held and cannot be
     *     fetched
     */
    @Nullable
    LocalizedMarket first(int id, @Nullable String variant, List<Locale> locales) {
        return InLocales.first(
                locales,
                locale -> catalog.holds(id, variant, locale),
                locale -> catalog.market(id, variant, locale),
                fetches);
    }
}
