package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A producer's status changed, its cause included: it went down or came back, or it is down for
 * another cause than before. Every change {@code onProducerStatusChange} hears is one of these, and
 * so is a change of the cause alone, which 0.0.x's callback does not hear. New in 1.0.
 *
 * @param producer the producer, as the producer manager has it; null for one its list does not have
 * @param down whether the producer is down now
 * @param delayed whether a session processes the producer's messages later than the maximum
 *     inactivity
 * @param reason the public reason, as {@code onProducerStatusChange} gives it
 * @param cause why, in more detail than the reason
 * @param at when it changed, by the SDK's clock
 */
public record ProducerCauseChange(
        long producerId,
        @Nullable Producer producer,
        boolean down,
        boolean delayed,
        ProducerStatusReason reason,
        ProducerStatusCause cause,
        Instant at) {}
