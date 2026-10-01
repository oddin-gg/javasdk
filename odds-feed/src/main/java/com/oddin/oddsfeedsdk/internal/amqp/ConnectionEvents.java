package com.oddin.oddsfeedsdk.internal.amqp;

import org.jspecify.annotations.Nullable;

/**
 * Where the transport reports the connection's state: the feed's events dispatcher. Each method runs
 * on the thread that saw the change, so an implementation hands the event over and returns.
 */
public interface ConnectionEvents {

    /** Reports nothing. */
    ConnectionEvents NONE = new ConnectionEvents() {};

    default void connecting() {}

    default void up() {}

    /** The connection was lost, not closed by the feed; reconnection starts. */
    default void down(String reason) {}

    /**
     * Another try at reconnecting, after {@code waitMillis}, and why the last one failed - so a
     * broker out of resources says so each time.
     */
    default void recovering(int attempt, long waitMillis, String reason) {}

    /**
     * No reconnect can succeed: the broker has refused the login or the virtual host for a minute,
     * three times at least, with no connection in between. The transport stops trying; the feed has
     * to be closed and made again.
     */
    default void fatal(String reason, @Nullable Throwable cause) {}
}
