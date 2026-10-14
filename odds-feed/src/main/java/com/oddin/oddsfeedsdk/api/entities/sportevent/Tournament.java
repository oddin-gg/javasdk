package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface Tournament extends LongTermEvent {
    @Nullable
    List<Competitor> getCompetitors();

    /**
     * The ids of the competitors {@link #getCompetitors()} lists, in the same order; none when the
     * tournament's info lists none. Loads what {@link #getCompetitors()} loads of the tournament,
     * its info in every locale, but starts no loading of the competitors' own profiles, and fails
     * as it does by the exception strategy. New in 1.0, as the Go SDK has it; an implementation of
     * the interface that does not override it lists the ids of its competitors.
     */
    default @Nullable List<URN> getCompetitorIds() {
        var competitors = getCompetitors();
        return competitors == null
                ? null
                : competitors.stream().map(Competitor::getId).toList();
    }

    @Nullable
    Date getStartDate();

    @Nullable
    Date getEndDate();

    @Nullable
    Integer getRiskTier();

    @Nullable
    String getAbbreviation(Locale locale);
}
