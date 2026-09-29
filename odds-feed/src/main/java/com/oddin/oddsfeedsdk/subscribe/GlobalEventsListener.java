package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;

/** Events of the feed as a whole, not of one session. */
public interface GlobalEventsListener {
    void onProducerStatusChange(ProducerStatus producerStatus);

    void onConnectionDown();

    void onEventRecoveryCompleted(URN eventId, long requestId);
}
