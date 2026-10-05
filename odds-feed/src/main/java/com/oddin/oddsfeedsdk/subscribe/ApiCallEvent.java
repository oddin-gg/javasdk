package com.oddin.oddsfeedsdk.subscribe;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP attempt of a call the SDK made to the API, answered or not: a retry is an attempt of its
 * own. New in 1.0.
 *
 * @param method the HTTP method
 * @param uri what was asked for, query included; the access token travels in a header, never here
 * @param status the HTTP status, or 0 when nothing answered
 * @param latency from sending the request to having read the answer, or to giving up
 * @param attempt 1 for the first attempt of a call, 2 for its first retry, and so on
 * @param failure why the attempt failed, or null when it succeeded
 * @param at when the attempt ended, by the SDK's clock
 */
public record ApiCallEvent(
        String method,
        URI uri,
        int status,
        Duration latency,
        int attempt,
        @Nullable Exception failure,
        Instant at) {}
