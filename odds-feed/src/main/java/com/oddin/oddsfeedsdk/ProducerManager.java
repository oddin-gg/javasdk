package com.oddin.oddsfeedsdk;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The producers the feed knows, and which of them the client takes messages from. */
public interface ProducerManager {
    Map<Long, Producer> getAvailableProducers();

    Map<Long, Producer> getActiveProducers();

    @Nullable
    Producer getProducer(long id);

    void setProducerState(long id, boolean enabled);

    void setProducerRecoveryFromTimestamp(long producerId, long timestamp);

    boolean isProducerEnabled(long id);

    boolean isProducerDown(long id);

    /**
     * The producers the list has as active that serve {@code scope}, by id, in the list's order: a
     * new map each time, as {@link #getActiveProducers()} gives. A producer listed with both scopes
     * is in either's. New in 1.0, as the Go SDK has it.
     */
    default Map<Long, Producer> getActiveProducersInScope(ProducerScope scope) {
        return Map.of();
    }

    /**
     * The producer's status as {@code onProducerStatusChange} last told it, or is about to: down or
     * up, delayed or not, and the reason, timed when it changed. For a client that polls, or that
     * missed a change. Null before the producer's first change since the feed opened - every
     * producer starts down, and 0.0.x told nothing until that changed - for a producer the list
     * does not have, and on a replay feed, which runs no recovery. New in 1.0, as the Go SDK has it.
     */
    default @Nullable ProducerStatus getProducerStatus(long id) {
        return null;
    }
}
