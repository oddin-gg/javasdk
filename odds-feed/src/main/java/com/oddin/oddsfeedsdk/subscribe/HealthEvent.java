package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A part of the feed changed its health: what the SDK's own watch found, also logged. New in 1.0.
 *
 * @param session the session, for {@link HealthComponent#SESSION}; null for the others
 * @param previous the health it had
 * @param state the health it has now
 * @param reason what changed, in words, for the log and the client
 * @param at when the SDK found it, by its clock
 */
public record HealthEvent(
        HealthComponent component,
        @Nullable OddsFeedSession session,
        HealthState previous,
        HealthState state,
        String reason,
        Instant at) {}
