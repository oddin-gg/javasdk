package com.oddin.oddsfeedsdk.api.entities;

import org.jspecify.annotations.Nullable;

public interface RecoveryInfo {
    long getAfter();

    long getTimestamp();

    long getRequestId();

    boolean getSuccessful();

    @Nullable
    Integer getNodeId();
}
