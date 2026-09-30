package com.oddin.oddsfeedsdk.internal.rest;

import java.net.URI;

/**
 * Where the client reports what it did: the feed's events dispatcher. Every method runs on the
 * thread that made the call, so an implementation hands the event over and returns; it must not
 * block or throw.
 */
public interface ApiEvents {

    /** Reports nothing. */
    ApiEvents NONE = new ApiEvents() {};

    /** Every HTTP attempt, whatever came of it. */
    default void called(ApiCall call) {}

    /**
     * The API refused the access token (401 or 403). No retry can fix that, so the feed treats it
     * as fatal; the call that got it fails as well.
     */
    default void refused(ApiCall call) {}

    /** A response fetched and decoded, for the raw API data callback. */
    default void received(URI uri, Object decoded, byte[] body) {}
}
