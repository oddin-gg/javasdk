package com.oddin.oddsfeedsdk.api.entities;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface Producer {
    long getId();

    String getName();

    String getDescription();

    long getLastMessageTimestamp();

    boolean isAvailable();

    boolean isEnabled();

    boolean isFlaggedDown();

    String getApiUrl();

    Set<ProducerScope> getProducerScopes();

    long getLastProcessedMessageGenTimestamp();

    long getProcessingQueDelay();

    @Nullable
    Instant getTimestampForRecovery();

    int getStatefulRecoveryWindowInMinutes();

    @Nullable
    RecoveryInfo getRecoveryInfo();
}
