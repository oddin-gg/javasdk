package com.oddin.oddsfeedsdk.internal.xml;

import java.io.Serial;

/** A message that could not be decoded: too large, not well-formed, or not a feed message. */
public final class DecodeException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    DecodeException(String message) {
        super(message);
    }

    DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
