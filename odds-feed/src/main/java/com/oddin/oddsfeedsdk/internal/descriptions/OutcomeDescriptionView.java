package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.api.factories.OutcomeDescription;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket.Outcome;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * An outcome of a market description, as the client holds it. Its name and description are looked
 * up in the locale they are asked in, by id, in constant time.
 */
final class OutcomeDescriptionView implements OutcomeDescription {

    private final String id;
    private final MarketDescriptionView market;

    OutcomeDescriptionView(String id, MarketDescriptionView market) {
        this.id = id;
        this.market = market;
    }

    @Override
    public String getId() {
        return id;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Override
    @SuppressWarnings("InlineMeSuggester") // the interface's method, which a caller calls
    public @Nullable Long getRefId() {
        return null;
    }

    /** Null when the market or the outcome is not described in {@code locale}. */
    @Override
    public @Nullable String getName(Locale locale) {
        Outcome outcome = market.outcome(id, locale);
        return outcome == null ? null : outcome.name();
    }

    /** Null when the outcome has none in {@code locale}, or is not described there. */
    @Override
    public @Nullable String getDescription(Locale locale) {
        Outcome outcome = market.outcome(id, locale);
        return outcome == null ? null : outcome.description();
    }

    @Override
    public String toString() {
        return "outcome " + id + " of " + market;
    }
}
