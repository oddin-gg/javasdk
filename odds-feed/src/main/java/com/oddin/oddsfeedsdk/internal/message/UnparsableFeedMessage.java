package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import org.jspecify.annotations.Nullable;

/**
 * A message the SDK could not read, for the match its routing key names. It has no producer, as in
 * 0.0.x, and no bytes when it was over the maximum message size.
 */
final class UnparsableFeedMessage implements UnparsableMessage<SportEvent> {

    private final SportEvent event;
    private final byte @Nullable [] raw;
    private final MessageTimestamp timestamp;

    UnparsableFeedMessage(SportEvent event, byte @Nullable [] raw, MessageTimestamp timestamp) {
        this.event = event;
        this.raw = raw;
        this.timestamp = timestamp;
    }

    @Override
    public SportEvent getEvent() {
        return event;
    }

    @Override
    public byte @Nullable [] getRawMessage() {
        return raw;
    }

    @Override
    public @Nullable Producer getProducer() {
        return null;
    }

    @Override
    public MessageTimestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "UnparsableFeedMessage(" + event.getId() + ")";
    }
}
