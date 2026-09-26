package com.oddin.oddsfeedsdk.exceptions;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public class GenericOdsFeedException extends OddsFeedSdkException {
    @Serial
    private static final long serialVersionUID = 1L;

    public GenericOdsFeedException(@Nullable String message, @Nullable Exception e) {
        super(message, e);
    }
}
