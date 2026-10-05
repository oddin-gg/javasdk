package com.oddin.oddsfeedsdk.subscribe;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The feed's broker connection changed state. New in 1.0.
 *
 * @param reason why it went down, or why the last try at reconnecting failed; null when it connects
 *     or is up
 * @param attempt which try at reconnecting is coming, from 1; 0 unless {@link
 *     ConnectionState#RECOVERING}
 * @param retryIn how long until that try; zero unless {@link ConnectionState#RECOVERING}
 * @param at when it changed, by the SDK's clock
 */
public record ConnectionStateChange(
        ConnectionState state, @Nullable String reason, int attempt, Duration retryIn, Instant at) {}
