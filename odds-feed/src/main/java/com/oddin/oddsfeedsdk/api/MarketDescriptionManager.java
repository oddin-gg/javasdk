package com.oddin.oddsfeedsdk.api;

import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Market descriptions and void reasons. */
public interface MarketDescriptionManager {
    /** In the default locale. */
    @Nullable
    List<MarketDescription> getMarketDescriptions();

    @Nullable
    List<MarketDescription> getMarketDescriptions(Locale locale);

    @Nullable
    MarketDescription getMarketDescription(int marketId, @Nullable String variant, Locale locale);

    void clearMarketDescription(int marketId, @Nullable String variant);

    @Nullable
    List<MarketVoidReason> getMarketVoidReasons();

    void clearMarketVoidReasons();

    /**
     * The void reasons fetched now, whatever is held, and held from then on; what was held stays
     * when the fetch fails, which follows the exception handling strategy as {@link
     * #getMarketVoidReasons()} does. A new list each time. New in 1.0, as the Go SDK has it.
     */
    default @Nullable List<MarketVoidReason> reloadMarketVoidReasons() {
        return null;
    }
}
