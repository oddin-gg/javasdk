package com.oddin.oddsfeedsdk.internal.clock;

/**
 * What a fact carries of when it happened, both readings taken at one moment on the thread that
 * saw it: the actor handles it later.
 *
 * @param wall epoch millis by the SDK's wall clock: what is published, and what is compared with a
 *     producer's own timestamps
 * @param mono millis on the monotonic clock, from {@link FeedClock#monoMillis()}: for durations
 *     between two local events, never to be compared with {@code wall} or published
 */
public record Stamp(long wall, long mono) {

    /** Both readings the same number, as {@link FeedClock#following} reads a clock that moves both. */
    public static Stamp of(long millis) {
        return new Stamp(millis, millis);
    }
}
