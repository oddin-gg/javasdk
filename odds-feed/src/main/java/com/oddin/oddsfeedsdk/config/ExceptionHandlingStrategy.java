package com.oddin.oddsfeedsdk.config;

/** What a getter does when it cannot load what it returns: throw, or return null. */
public enum ExceptionHandlingStrategy {
    THROW,
    CATCH
}
