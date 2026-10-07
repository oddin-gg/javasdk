package com.oddin.oddsfeedsdk.api.factories;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface MarketDescription {
    int getId();

    /**
     * Always 0, as 0.0.x read the absent attribute, and reading it loads nothing. Declared nullable,
     * as 0.0.x declared it.
     *
     * @deprecated the feed never sends this value.
     */
    @Deprecated
    @Nullable
    Integer getRefId();

    @Nullable
    String getName(Locale locale);

    List<OutcomeDescription> getOutcomes();

    @Nullable
    String getVariant();

    @Nullable
    List<Specifier> getSpecifiers();

    @Nullable
    String getIncludesOutcomesOfType();

    @Nullable
    OutcomeType getOutcomeType();

    List<String> getGroups();
}
