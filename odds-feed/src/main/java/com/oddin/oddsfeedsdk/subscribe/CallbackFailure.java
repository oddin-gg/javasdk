package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A callback of the client's threw, or a step of the SDK's own handling of a message failed. Either
 * way the SDK went on: a message it was handling was acknowledged, and the next one is handled as
 * usual. New in 1.0.
 *
 * @param callback the callback that threw, such as {@code onOddsChange}, or the step of the SDK
 *     that failed, such as {@code build}
 * @param clientCode true when the client's own code threw, false when the SDK failed
 * @param exception what was thrown
 * @param session the session whose message it was, null for a callback of the feed as a whole
 * @param at when it was caught, by the SDK's clock
 */
public record CallbackFailure(
        String callback,
        boolean clientCode,
        Throwable exception,
        @Nullable OddsFeedSession session,
        Instant at) {}
