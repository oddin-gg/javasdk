package com.oddin.oddsfeedsdk.internal.catalog;

import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescription;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One locale's list of market descriptions, as the list endpoint sent it. Immutable. */
final class MarketList {

    private final Map<MarketKey, LocalizedMarket> byKey;

    private MarketList(Map<MarketKey, LocalizedMarket> byKey) {
        this.byKey = byKey;
    }

    /** The markets of a list in its order; of two under one key the later. */
    static MarketList of(List<RAMarketDescription> markets) {
        var byKey = new LinkedHashMap<MarketKey, LocalizedMarket>();
        for (RAMarketDescription market : markets) {
            LocalizedMarket described = LocalizedMarket.from(market, market.getVariant());
            byKey.put(described.key(), described);
        }
        return new MarketList(Collections.unmodifiableMap(byKey));
    }

    @Nullable
    LocalizedMarket get(MarketKey key) {
        return byKey.get(key);
    }

    /** By key, in the order of the list. */
    Map<MarketKey, LocalizedMarket> byKey() {
        return byKey;
    }

    int size() {
        return byKey.size();
    }
}
