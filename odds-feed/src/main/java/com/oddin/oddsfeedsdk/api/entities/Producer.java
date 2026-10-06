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

    /**
     * Where a recovery of this producer has to start for the client to miss nothing: the value to
     * persist, and to pass back to {@link
     * com.oddin.oddsfeedsdk.ProducerManager#setProducerRecoveryFromTimestamp(long, long)} after a
     * restart.
     *
     * <p>Until the feed has opened, it is what the client set there. Once open, it is the
     * producer's resume point: the oldest point any session that receives the producer still
     * needs - the start of the oldest gap no recovery has covered yet, else the oldest point up to
     * which a session has processed the producer's messages. It never runs ahead of what the
     * sessions' callbacks have finished, so messages queued but not processed are not lost across
     * a restart; it can lag a little behind them. A session closed while the feed runs no longer
     * counts. Once the feed begins to close, the point only goes back, whatever order its sessions
     * close in, and it keeps its last value after, so a client reads it at shutdown. 0.0.x reported
     * the last alive while the producer was up, which can be ahead of what was processed.
     *
     * <p>It is not clamped to the producer's stateful recovery window: after a long downtime the
     * setter throws {@link IllegalArgumentException} for it. Catch that and pass 0, for a full
     * recovery:
     *
     * <pre>{@code
     * try {
     *     producerManager.setProducerRecoveryFromTimestamp(id, saved);
     * } catch (IllegalArgumentException tooOld) {
     *     producerManager.setProducerRecoveryFromTimestamp(id, 0);
     * }
     * }</pre>
     *
     * @return the point, as the producer's clock has it, or null for a full recovery
     */
    @Nullable
    Instant getTimestampForRecovery();

    int getStatefulRecoveryWindowInMinutes();

    @Nullable
    RecoveryInfo getRecoveryInfo();
}
