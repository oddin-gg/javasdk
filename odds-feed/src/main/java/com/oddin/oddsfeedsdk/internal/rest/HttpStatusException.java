package com.oddin.oddsfeedsdk.internal.rest;

import java.io.IOException;
import java.io.Serial;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** The API answered with a status that is not a success: the cause of the call's ApiException. */
public final class HttpStatusException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    HttpStatusException(String method, URI uri, int status) {
        super(method + " " + uri + " answered " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** The status an API call failed with, from anywhere in the cause chain, or 0 when none did. */
    public static int statusOf(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpStatusException status) {
                return status.status;
            }
        }
        return 0;
    }

    /** Whether the API refused the access token, which no retry changes. */
    public static boolean refused(@Nullable Throwable failure) {
        int status = statusOf(failure);
        return status == 401 || status == 403;
    }
}
