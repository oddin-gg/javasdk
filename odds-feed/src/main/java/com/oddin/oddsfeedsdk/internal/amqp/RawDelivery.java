package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One message as the broker delivered it, before anything reads it.
 *
 * @param body the body, or null when it was over the maximum message size: it is not decoded but
 *     counted, reported as unparsable and acknowledged
 * @param size the body's length in bytes
 * @param deliveryTag what acknowledges it, on the channel of {@code epoch}
 * @param epoch the channel it came from; an acknowledgement for an older one is skipped
 * @param receivedAt when the SDK received it, by its own clock
 * @param sentAt the message's AMQP timestamp, when it has one
 */
@SuppressWarnings("ArrayRecordComponent") // handed on, never compared or hashed
public record RawDelivery(
        byte @Nullable [] body,
        int size,
        String routingKey,
        long deliveryTag,
        long epoch,
        Instant receivedAt,
        @Nullable Instant sentAt) {

    public boolean oversized() {
        return body == null;
    }
}
