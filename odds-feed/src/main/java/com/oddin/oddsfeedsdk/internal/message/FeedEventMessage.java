package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import java.util.Date;
import org.jspecify.annotations.Nullable;

/** What every message about an event has: the event, the producer, the times, the raw bytes. */
abstract class FeedEventMessage implements EventMessage<SportEvent> {

    private final SportEvent event;
    private final @Nullable Long requestId;
    private final byte[] raw;
    private final Producer producer;
    private final MessageTimestamp timestamp;

    FeedEventMessage(
            SportEvent event, @Nullable Long requestId, byte[] raw, Producer producer, MessageTimestamp timestamp) {
        this.event = event;
        this.requestId = requestId;
        this.raw = raw;
        this.producer = producer;
        this.timestamp = timestamp;
    }

    @Override
    public SportEvent getEvent() {
        return event;
    }

    @Override
    public @Nullable Long getRequestId() {
        return requestId;
    }

    /** The bytes as the broker delivered them; the same array each time, as in 0.0.x. */
    @Override
    public byte[] getRawMessage() {
        return raw;
    }

    @Override
    public Producer getProducer() {
        return producer;
    }

    @Override
    public MessageTimestamp getTimestamp() {
        return timestamp;
    }

    static @Nullable Date date(@Nullable Long epochMillis) {
        return epochMillis == null ? null : new Date(epochMillis);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + event.getId() + " from " + producer.getId() + " at "
                + timestamp.getCreated() + (requestId == null ? "" : ", request " + requestId) + ")";
    }
}
