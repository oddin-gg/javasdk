package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface Sport extends SportSummary {
    @Nullable
    List<Tournament> getTournaments();

    /**
     * The ids of the tournaments {@link #getTournaments()} lists, in the same order; none when the
     * sport has none. Reads the same tournament list, loaded in the first locale of the sport, and
     * fails as {@link #getTournaments()} does by the exception strategy; no tournament is loaded.
     * New in 1.0, as the Go SDK has it; an implementation of the interface that does not override
     * it lists the ids of its tournaments.
     */
    default @Nullable List<URN> getTournamentIds() {
        var tournaments = getTournaments();
        return tournaments == null
                ? null
                : tournaments.stream().map(Tournament::getId).toList();
    }
}
