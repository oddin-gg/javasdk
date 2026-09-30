package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo;
import org.jspecify.annotations.Nullable;

/**
 * The last recovery of a producer, as the client sees it.
 *
 * @param after what it asked for messages since, epoch millis, 0 for a full snapshot
 * @param timestamp when it was asked, epoch millis
 */
public record Recovery(
        long after,
        long timestamp,
        long requestId,
        @Nullable Integer nodeId,
        boolean successful) implements RecoveryInfo {

    @Override
    public long getAfter() {
        return after;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public long getRequestId() {
        return requestId;
    }

    @Override
    public boolean getSuccessful() {
        return successful;
    }

    @Override
    public @Nullable Integer getNodeId() {
        return nodeId;
    }
}
