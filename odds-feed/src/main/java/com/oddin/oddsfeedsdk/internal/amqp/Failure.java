package com.oddin.oddsfeedsdk.internal.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.io.Serial;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What kind of failure a failed connection or a lost one is, which decides what reconnecting does.
 */
enum Failure {
    /**
     * The broker refused the login or the virtual host. Permanent once refusals, three at least,
     * have gone on for a minute with no connection in between; until then it can be the broker's
     * auth backend being deployed, or a virtual host being written.
     */
    REFUSED,
    /** The broker is at a limit of connections or queues: transient, retried with a long pause. */
    RESOURCES,
    /** Anything else - the network, a restart, a lost heartbeat: retried with backoff. */
    NETWORK;

    /** What the access token reads as in anything the SDK says: the broker quotes it as the user. */
    static final String TOKEN = "<access token>";

    /**
     * The kind of {@code failure}, from its cause chain. The client's "possible authentication
     * failure" is any close during the login, a broker shutting down included, so only the broker's
     * own reply decides, on the connection or on a channel: a limit is resources; {@code
     * ACCESS_REFUSED} or {@code NOT_ALLOWED} otherwise a refusal.
     */
    static Failure of(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailureException) {
                return REFUSED;
            }
            if (cause instanceof ShutdownSignalException shutdown) {
                // what the broker quotes - the user, which is the token, a queue, a virtual host - is
                // left out, so a token with "limit" in it does not read as a limit
                String text = String.valueOf(replyText(shutdown))
                        .replaceAll("'[^']*'", "''")
                        .toLowerCase(Locale.ROOT);
                if (text.contains("limit")) {
                    return RESOURCES;
                }
                int code = replyCode(shutdown);
                if (code == AMQP.ACCESS_REFUSED || code == AMQP.NOT_ALLOWED) {
                    return REFUSED;
                }
            }
        }
        return NETWORK;
    }

    /**
     * A line for the events and exceptions: the broker's reason when it gave one, with the access
     * token taken out - the broker quotes the user, and the user is the token.
     */
    static String describe(@Nullable Throwable failure, String token) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException shutdown && replyText(shutdown) != null) {
                return redact(replyCode(shutdown) + " " + replyText(shutdown), token);
            }
            if (cause.getMessage() != null && cause.getCause() == null) {
                return redact(cause.getMessage(), token);
            }
        }
        return redact(String.valueOf(failure), token);
    }

    /**
     * {@code failure} as a cause to hand on: the same kind and stack, its message without the token.
     */
    static Exception redacted(Throwable failure, String token) {
        var copy = new Redacted(failure.getClass().getName() + ": " + describe(failure, token));
        copy.setStackTrace(failure.getStackTrace());
        return copy;
    }

    static String redact(String text, String token) {
        return token.isEmpty() ? text : text.replace(token, TOKEN);
    }

    /** The broker's reply code, of a connection's close or a channel's; 0 for none. */
    private static int replyCode(ShutdownSignalException shutdown) {
        if (shutdown.getReason() instanceof AMQP.Connection.Close close) {
            return close.getReplyCode();
        }
        if (shutdown.getReason() instanceof AMQP.Channel.Close close) {
            return close.getReplyCode();
        }
        return 0;
    }

    private static @Nullable String replyText(ShutdownSignalException shutdown) {
        if (shutdown.getReason() instanceof AMQP.Connection.Close close) {
            return close.getReplyText();
        }
        if (shutdown.getReason() instanceof AMQP.Channel.Close close) {
            return close.getReplyText();
        }
        return null;
    }

    /** A failure whose message has had the token taken out. */
    private static final class Redacted extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        Redacted(String message) {
            super(message);
        }
    }
}
