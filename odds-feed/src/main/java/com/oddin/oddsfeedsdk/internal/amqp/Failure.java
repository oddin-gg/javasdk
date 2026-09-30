package com.oddin.oddsfeedsdk.internal.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What kind of failure a failed connection or a lost one is, which decides what reconnecting does.
 */
enum Failure {
    /**
     * The broker refused the login or the virtual host. Permanent after three in a minute; one alone
     * can be a blip of the broker's auth backend.
     */
    REFUSED,
    /** The broker is out of connections or queues: transient, retried with a long pause. */
    RESOURCES,
    /** Anything else - the network, a restart, a lost heartbeat: retried with backoff. */
    NETWORK;

    static Failure of(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailureException
                    || cause instanceof PossibleAuthenticationFailureException) {
                return REFUSED;
            }
            if (cause instanceof ShutdownSignalException shutdown
                    && shutdown.getReason() instanceof AMQP.Connection.Close close) {
                String text = String.valueOf(close.getReplyText()).toLowerCase(Locale.ROOT);
                if (text.contains("limit")) {
                    return RESOURCES;
                }
                if (close.getReplyCode() == AMQP.ACCESS_REFUSED || close.getReplyCode() == AMQP.NOT_ALLOWED) {
                    return REFUSED;
                }
            }
        }
        return NETWORK;
    }

    /** A line for the events and exceptions: the broker's reason when it gave one. */
    static String describe(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException shutdown
                    && shutdown.getReason() instanceof AMQP.Connection.Close close) {
                return close.getReplyCode() + " " + close.getReplyText();
            }
            if (cause.getMessage() != null && cause.getCause() == null) {
                return cause.getMessage();
            }
        }
        return String.valueOf(failure);
    }
}
