package com.oddin.oddsfeedsdk.internal.catalog;

import org.jspecify.annotations.Nullable;

/**
 * What a market description is cached under: the market's id and its variant, as in 0.0.x.
 *
 * @param variant null for none; an empty variant is none too, since the API has no such variant
 */
public record MarketKey(int id, @Nullable String variant) {

    /**
     * The variants whose outcomes depend on the variant, such as the players of one match: each is
     * fetched on its own, from the variant's endpoint.
     */
    static final String DYNAMIC_OUTCOMES = "od:dynamic_outcomes:";

    public MarketKey {
        if (variant != null && variant.isEmpty()) {
            variant = null;
        }
    }

    public static MarketKey of(int id, @Nullable String variant) {
        return new MarketKey(id, variant);
    }

    /** Whether its description is fetched on its own rather than listed: a dynamic-outcome variant. */
    public boolean isDynamic() {
        return variant != null && variant.startsWith(DYNAMIC_OUTCOMES);
    }

    @Override
    public String toString() {
        return id + (variant == null ? "" : " " + variant);
    }
}
