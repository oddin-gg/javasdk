package com.oddin.oddsfeedsdk.api.entities.sportevent;

import org.jspecify.annotations.Nullable;

public interface LongTermEvent extends SportEvent {
    @Nullable SportSummary getSport();
}
