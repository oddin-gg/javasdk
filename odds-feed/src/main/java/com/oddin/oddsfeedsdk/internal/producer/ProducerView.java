package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A producer as the client holds it. As in 0.0.x, what the feed sees of the producer - down, its
 * timestamps, its recovery - reads as it is now, while whether it is enabled reads as it was when
 * the client got this object.
 */
final class ProducerView implements Producer {

    private final AtomicReference<ProducerState> state;
    private final ProducerState atCreation;
    private final InstantSource clock;

    ProducerView(AtomicReference<ProducerState> state, InstantSource clock) {
        this.state = state;
        this.atCreation = state.get();
        this.clock = clock;
    }

    @Override
    public long getId() {
        return atCreation.id();
    }

    @Override
    public String getName() {
        return atCreation.name();
    }

    @Override
    public String getDescription() {
        return atCreation.description();
    }

    @Override
    public long getLastMessageTimestamp() {
        return state.get().lastMessageTimestamp();
    }

    @Override
    public boolean isAvailable() {
        return atCreation.available();
    }

    @Override
    public boolean isEnabled() {
        return atCreation.enabled();
    }

    @Override
    public boolean isFlaggedDown() {
        return state.get().down();
    }

    @Override
    public String getApiUrl() {
        return atCreation.apiUrl();
    }

    @Override
    public Set<ProducerScope> getProducerScopes() {
        return atCreation.scopes();
    }

    @Override
    public long getLastProcessedMessageGenTimestamp() {
        return state.get().lastProcessedMessageGenTimestamp();
    }

    @Override
    public long getProcessingQueDelay() {
        return clock.millis() - state.get().lastProcessedMessageGenTimestamp();
    }

    /** The last alive's generation time once there has been one, before that the client's recovery start. */
    @Override
    public @Nullable Instant getTimestampForRecovery() {
        ProducerState now = state.get();
        long millis =
                now.lastAliveReceivedGenTimestamp() == 0 ? now.recoveryFrom() : now.lastAliveReceivedGenTimestamp();
        return millis > 0 ? Instant.ofEpochMilli(millis) : null;
    }

    @Override
    public int getStatefulRecoveryWindowInMinutes() {
        return atCreation.statefulRecoveryWindowInMinutes();
    }

    @Override
    public @Nullable RecoveryInfo getRecoveryInfo() {
        return state.get().recoveryInfo();
    }

    @Override
    public String toString() {
        return atCreation.id() + "-" + atCreation.name();
    }
}
