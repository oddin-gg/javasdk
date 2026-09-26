package com.oddin.oddsfeedsdk.exceptions;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public abstract class OddsFeedSdkException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    protected OddsFeedSdkException(@Nullable String message, @Nullable Exception e) {
        super(message, e);
    }
}
