package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transport's one connection listener, telling each of the feed's in turn: the recovery actor
 * first, so a producer's state follows the connection before the client hears of it, then the
 * events dispatcher. One that throws is logged, and the next is told all the same.
 */
final class ConnectionTee implements ConnectionEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionTee.class);

    private final List<ConnectionEvents> told;

    /** @param told in the order they are told */
    ConnectionTee(List<? extends ConnectionEvents> told) {
        this.told = List.copyOf(told);
    }

    /** Who it tells, in order. */
    List<ConnectionEvents> told() {
        return told;
    }

    @Override
    public void connecting() {
        tell("connecting", ConnectionEvents::connecting);
    }

    @Override
    public void up() {
        tell("up", ConnectionEvents::up);
    }

    @Override
    public void down(String reason) {
        tell("down", listener -> listener.down(reason));
    }

    @Override
    public void recovering(int attempt, long waitMillis, String reason) {
        tell("recovering", listener -> listener.recovering(attempt, waitMillis, reason));
    }

    @Override
    public void fatal(String reason, @Nullable Throwable cause) {
        tell("fatal", listener -> listener.fatal(reason, cause));
    }

    private void tell(String event, Consumer<ConnectionEvents> tell) {
        for (ConnectionEvents listener : told) {
            try {
                tell.accept(listener);
            } catch (RuntimeException e) {
                LOG.error("The connection listener threw on {}; the others are told", event, e);
            }
        }
    }
}
