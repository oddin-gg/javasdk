package com.oddin.oddsfeedsdk.internal.recovery;

import java.util.function.LongPredicate;
import java.util.random.RandomGenerator;

/**
 * Recovery request ids: from a random start in the positive 31-bit range, one up each time, and a
 * new random start on reaching the end of the range. The random start makes it unlikely that a feed
 * restarted within the same second reuses the ids of the one before. 0.0.x started below 20 000.
 *
 * <p>Not safe for concurrent use: the recovery actor's.
 */
final class RequestIds {

    /** The largest id; the API reads request ids as 32-bit numbers. */
    static final long MAX = Integer.MAX_VALUE;

    private final RandomGenerator random;
    private long last;

    RequestIds(RandomGenerator random) {
        this.random = random;
        this.last = seed();
    }

    /** The next id that {@code inFlight} does not hold. */
    long next(LongPredicate inFlight) {
        do {
            last = (last >= MAX ? seed() : last) + 1;
        } while (inFlight.test(last));
        return last;
    }

    /** One before the first id, so the first is in 1 to {@link #MAX}. */
    private long seed() {
        return random.nextLong(MAX);
    }
}
