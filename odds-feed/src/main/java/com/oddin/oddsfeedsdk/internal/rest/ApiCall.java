package com.oddin.oddsfeedsdk.internal.rest;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One HTTP attempt of an API call, answered or not.
 *
 * @param method the HTTP method
 * @param uri what was asked for, query included
 * @param status the HTTP status, or 0 when nothing answered
 * @param latency from sending the request to having read the answer, or to giving up
 * @param attempt 1 for the first attempt of a call, 2 for its first retry, and so on
 * @param failure why the attempt failed, or null when it succeeded
 */
public record ApiCall(
        String method,
        URI uri,
        int status,
        Duration latency,
        int attempt,
        @Nullable Exception failure) {}
