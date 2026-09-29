package com.oddin.oddsfeedsdk.mq.entities;

import org.jspecify.annotations.Nullable;

public interface OutcomeSettlement extends Outcome {
    @Nullable
    VoidFactor getVoidFactor();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    Double getDeadHeatFactor();

    OutcomeResult getOutcomeResult();
}
