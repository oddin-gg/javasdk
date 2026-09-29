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
}
