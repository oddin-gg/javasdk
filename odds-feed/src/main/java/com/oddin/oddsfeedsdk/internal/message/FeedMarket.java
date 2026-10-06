package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.entities.Market;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A market of a message: its id and specifiers as the feed sent them, its name from the catalog. */
class FeedMarket implements Market {

    private final int id;
    private final Map<String, String> specifiers;
    final MarketNames names;

    FeedMarket(int id, Map<String, String> specifiers, MarketNames names) {
        this.id = id;
        this.specifiers = specifiers;
        this.names = names;
    }

    @Override
    public int getId() {
        return id;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable Integer getRefId() {
        return null;
    }

    /** A new map on each call, in the feed's order, which the client may change: the market keeps its own. */
    @Override
    public Map<String, String> getSpecifiers() {
        return new LinkedHashMap<>(specifiers);
    }

    /** The name in the default locale. */
    @Override
    public @Nullable String getName() {
        return names.market(names.defaultLocale());
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return names.market(locale);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + id + (specifiers.isEmpty() ? "" : " " + specifiers) + ")";
    }
}
