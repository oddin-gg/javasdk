package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
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
     * the interface that does not override it lists the ids of its competitors, in a list of the
     * caller's own, as the SDK's tournaments do.
     */
    default @Nullable List<URN> getCompetitorIds() {
        var competitors = getCompetitors();
        return competitors == null
                ? null
                : new ArrayList<>(competitors.stream().map(Competitor::getId).toList());
    }

    @Nullable
    Date getStartDate();

    @Nullable
    Date getEndDate();

    @Nullable
    Integer getRiskTier();

    @Nullable
    String getAbbreviation(Locale locale);

    /**
     * The location of the tournament's icon, as the tournament's info in the API says it; null when
     * the info sends none. Only the info carries it, in every locale the same, so the value does not
     * depend on the locale; a later response without it, in any locale, keeps what an earlier one
     * said. Loads what the other getters load of the tournament, its info in every locale, and fails
     * as they do by the exception strategy. New in 1.0, as the Go SDK has it; an implementation of
     * the interface that does not override it answers null.
     */
    default @Nullable String getIconPath() {
        return null;
    }
}
