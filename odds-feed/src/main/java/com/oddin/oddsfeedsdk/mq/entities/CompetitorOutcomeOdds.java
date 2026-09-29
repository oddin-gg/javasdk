package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.HomeAway;
import org.jspecify.annotations.Nullable;

public interface CompetitorOutcomeOdds extends OutcomeOdds {
    /** @deprecated the feed never sends this value. */
    @Deprecated
    HomeAway getHomeOrAwayTeam();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    Competitor getTeam();
}
