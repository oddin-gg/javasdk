package com.oddin.oddsfeedsdk.mq.entities;

import org.jspecify.annotations.Nullable;

public interface OutcomeOdds extends OutcomeProbabilities {
    boolean isPlayerOutcome();

    @Nullable Double getOdds(OddsDisplayType oddsDisplayType);

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable AdditionalProbabilities getAdditionalProbabilities();
}
