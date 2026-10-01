package com.oddin.oddsfeedsdk.internal.catalog;

import static java.util.Objects.requireNonNullElse;

import com.oddin.oddsfeedsdk.api.factories.OutcomeType;
import com.oddin.oddsfeedsdk.schema.rest.v1.RADescSpecifiers;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescription;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAOutcomeDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A market's description in one locale, as the API sent it. Immutable.
 *
 * @param variant the variant it was asked for or listed with, null for none
 * @param outcomes in the order the API sent them; empty when it sent none
 * @param specifiers null when the API sent none, as in 0.0.x
 * @param groups the groups it is in, split as 0.0.x split them; empty when it is in none
 * @param outcomeType as sent, such as {@code player}; see {@link #type()}
 */
public record LocalizedMarket(
        int id,
        @Nullable String variant,
        String name,
        List<Outcome> outcomes,
        @Nullable List<Specifier> specifiers,
        List<String> groups,
        @Nullable String includesOutcomesOfType,
        @Nullable String outcomeType) {

    public LocalizedMarket {
        outcomes = List.copyOf(outcomes);
        specifiers = specifiers == null ? null : List.copyOf(specifiers);
        groups = List.copyOf(groups);
    }

    /** The outcome type as the public enum, or null when there is none or it is one the SDK does not know. */
    public @Nullable OutcomeType type() {
        if (outcomeType == null) {
            return null;
        }
        for (OutcomeType type : OutcomeType.values()) {
            if (type.name().equals(outcomeType.toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }

    /** The key it is cached and asked for under. */
    public MarketKey key() {
        return MarketKey.of(id, variant);
    }

    /**
     * What the API sent of one market. {@code variant} is the one it was asked for, which a variant's
     * own endpoint may leave out of its answer; for a list it is the one the list names.
     */
    static LocalizedMarket from(RAMarketDescription market, @Nullable String variant) {
        var outcomes = new ArrayList<Outcome>();
        @Nullable RAOutcomeDescription sentOutcomes = market.getOutcomes();
        if (sentOutcomes != null) {
            for (RAOutcomeDescription.Outcome outcome : sentOutcomes.getOutcome()) {
                @Nullable String id = outcome.getId();
                if (id != null) {
                    outcomes.add(new Outcome(id, requireNonNullElse(outcome.getName(), ""), outcome.getDescription()));
                }
            }
        }
        List<Specifier> specifiers = null;
        @Nullable RADescSpecifiers sentSpecifiers = market.getSpecifiers();
        if (sentSpecifiers != null) {
            specifiers = new ArrayList<>();
            for (RADescSpecifiers.Specifier specifier : sentSpecifiers.getSpecifier()) {
                specifiers.add(new Specifier(
                        requireNonNullElse(specifier.getName(), ""), requireNonNullElse(specifier.getType(), "")));
            }
        }
        @Nullable String groups = market.getGroups();
        return new LocalizedMarket(
                market.getId(),
                MarketKey.of(market.getId(), variant).variant(),
                requireNonNullElse(market.getName(), ""),
                outcomes,
                specifiers,
                // as Kotlin's split did: "a|" is "a" and ""
                groups == null ? List.of() : List.of(groups.split("\\|", -1)),
                market.getIncludesOutcomesOfType(),
                market.getOutcomeType());
    }

    /** An outcome of a market, in the market's locale. */
    public record Outcome(String id, String name, @Nullable String description) {}

    /** A specifier a market takes, such as its variant. */
    public record Specifier(String name, String type) {}
}
