package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.OutcomeDescription;
import com.oddin.oddsfeedsdk.api.factories.OutcomeType;
import com.oddin.oddsfeedsdk.api.factories.Specifier;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket.Outcome;
import com.oddin.oddsfeedsdk.internal.catalog.MarketKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A market description as the client holds it, in the locales it was got in. As in 0.0.x, its id,
 * variant and outcome types read as they were when the client got it, and the rest is read from
 * the catalog on each call, so it reads what is held now. Nothing is copied: a name is looked up
 * when it is asked for, in the locale it is asked in.
 *
 * <p>The description gone from the catalog - removed upstream, say - is an {@link
 * com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException} under {@code THROW}, as in 0.0.x; under
 * {@code CATCH} the name and the specifiers are null, the outcomes and the groups empty.
 */
final class MarketDescriptionView implements MarketDescription {

    private static final String WHAT = "market description";

    private final MarketReads reads;
    private final MarketKey key;
    private final @Nullable String includesOutcomesOfType;
    private final @Nullable OutcomeType outcomeType;
    private final List<Locale> locales;

    /**
     * @param described the description as it was got, in the first of {@code locales} that has it
     * @param locales the locales it was got in, not empty
     */
    MarketDescriptionView(MarketReads reads, LocalizedMarket described, List<Locale> locales) {
        this.reads = reads;
        this.key = described.key();
        this.includesOutcomesOfType = described.includesOutcomesOfType();
        this.outcomeType = described.type();
        this.locales = locales;
    }

    @Override
    public int getId() {
        return key.id();
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Override
    @SuppressWarnings("InlineMeSuggester") // the interface's method, which a caller calls
    public @Nullable Integer getRefId() {
        return null;
    }

    @Override
    public @Nullable String getName(Locale locale) {
        LocalizedMarket described = described(List.of(locale));
        return described == null ? null : described.name();
    }

    @Override
    public List<OutcomeDescription> getOutcomes() {
        LocalizedMarket described = described(locales);
        if (described == null) {
            return List.of();
        }
        var outcomes = new ArrayList<OutcomeDescription>(described.outcomes().size());
        for (Outcome outcome : described.outcomes()) {
            outcomes.add(new OutcomeDescriptionView(outcome.id(), this));
        }
        return outcomes;
    }

    @Override
    public @Nullable String getVariant() {
        return key.variant();
    }

    @Override
    public @Nullable List<Specifier> getSpecifiers() {
        LocalizedMarket described = described(locales);
        List<LocalizedMarket.Specifier> specifiers = described == null ? null : described.specifiers();
        return specifiers == null ? null : Collections.unmodifiableList(specifiers);
    }

    @Override
    public @Nullable String getIncludesOutcomesOfType() {
        return includesOutcomesOfType;
    }

    @Override
    public @Nullable OutcomeType getOutcomeType() {
        return outcomeType;
    }

    @Override
    public List<String> getGroups() {
        LocalizedMarket described = described(locales);
        return described == null ? List.of() : described.groups();
    }

    /**
     * The outcome {@code id} in {@code locale}, null when the market or the outcome is not there
     * then, which 0.0.x answered with null too; a fetch that fails, as the strategy says.
     */
    @Nullable
    Outcome outcome(String id, Locale locale) {
        return reads.strategy()
                .read(
                        () -> {
                            LocalizedMarket described = reads.catalog().market(key.id(), key.variant(), locale);
                            return described == null ? null : described.outcome(id);
                        },
                        "an outcome of " + WHAT,
                        key);
    }

    @Override
    public String toString() {
        return WHAT + " " + key + " in " + locales;
    }

    /** The description in the first of {@code in} that has it, failing as the strategy says. */
    private @Nullable LocalizedMarket described(List<Locale> in) {
        return reads.strategy()
                .read(() -> reads.strategy().found(reads.first(key.id(), key.variant(), in), WHAT, key, in), WHAT, key);
    }
}
