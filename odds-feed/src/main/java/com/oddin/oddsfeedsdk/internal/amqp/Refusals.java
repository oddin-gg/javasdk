package com.oddin.oddsfeedsdk.internal.amqp;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The refused logins that count: those within a window of each other since the last successful
 * connection. Enough of them mean the refusal is for real, not a blip of the broker's auth backend.
 * Used under the transport's lock.
 */
final class Refusals {

    private final int enough;
    private final Duration window;
    private final InstantSource clock;
    private final Deque<Instant> recent = new ArrayDeque<>();

    Refusals(int enough, Duration window, InstantSource clock) {
        this.enough = enough;
        this.window = window;
        this.clock = clock;
    }

    /** Counts one; true once there are enough within the window. */
    boolean refusedTooOften() {
        Instant now = clock.instant();
        recent.addLast(now);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(window))) {
            recent.removeFirst();
        }
        return recent.size() >= enough;
    }

    /** A connection succeeded: the count starts again. */
    void clear() {
        recent.clear();
    }
}
