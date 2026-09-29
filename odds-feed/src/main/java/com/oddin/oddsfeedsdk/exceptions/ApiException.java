package com.oddin.oddsfeedsdk.exceptions;

import com.oddin.oddsfeedsdk.schema.rest.v1.RAError;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** A call to the API failed; with the error the API returned, when it returned one. */
public final class ApiException extends OddsFeedSdkException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ApiException(@Nullable String message, @Nullable RAError error, @Nullable Exception e) {
        super(error == null ? message : error.getMessage() + " - " + error.getAction(), e);
    }

    /** For Kotlin callers, who could leave out the error and the cause in 0.0.x. */
    public ApiException(@Nullable String message) {
        this(message, null, null);
    }

    /** For Kotlin callers, who could leave out the cause in 0.0.x. */
    public ApiException(@Nullable String message, @Nullable RAError error) {
        this(message, error, null);
    }
}
