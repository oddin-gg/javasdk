package com.oddin.oddsfeedsdk;

import com.oddin.oddsfeedsdk.api.entities.Producer;
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
}
