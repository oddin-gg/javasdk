package com.oddin.oddsfeedsdk.exceptions;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public class UnsupportedUrnFormatException extends OddsFeedSdkException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UnsupportedUrnFormatException(@Nullable String message, @Nullable Exception e) {
        super(message, e);
    }
}
