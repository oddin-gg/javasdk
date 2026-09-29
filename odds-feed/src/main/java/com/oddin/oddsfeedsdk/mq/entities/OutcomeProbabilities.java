package com.oddin.oddsfeedsdk.mq.entities;

import org.jspecify.annotations.Nullable;

public interface OutcomeProbabilities extends Outcome {
    boolean isActive();

    @Nullable
    Double getProbability();
}
