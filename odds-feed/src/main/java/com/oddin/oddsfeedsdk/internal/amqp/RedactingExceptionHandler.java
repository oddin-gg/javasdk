package com.oddin.oddsfeedsdk.internal.amqp;

import com.rabbitmq.client.impl.ForgivingExceptionHandler;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's reports of what failed in its callbacks, logged with the access token taken out: the
 * client names a channel by its connection, and a connection by its user, which is the token. Like
 * the forgiving handler it extends, and unlike the client's default, it closes nothing - reconnecting
 * and reopening channels is the transport's - and a socket closed or reset, which the transport
 * recovers from, is one warning line rather than an error with its stack.
 */
final class RedactingExceptionHandler extends ForgivingExceptionHandler {

    /** Where the reports go: the SDK's log, or a test's record. */
    interface Log {
        void warn(String message);

        void error(String message, Throwable e);
    }

    /** What the forgiving handler takes for a socket closed or reset. */
    private static final Set<String> CLOSED_OR_RESET =
            Set.of("Connection reset", "Socket closed", "Connection reset by peer");

    private static final Logger LOG = LoggerFactory.getLogger(AmqpTransport.class);

    private final String token;
    private final Log log;

    RedactingExceptionHandler(String token) {
        this(token, new Log() {
            @Override
            public void warn(String message) {
                LOG.warn(message);
            }

            @Override
            public void error(String message, Throwable e) {
                LOG.error(message, e);
            }
        });
    }

    RedactingExceptionHandler(String token, Log log) {
        this.token = token;
        this.log = log;
    }

    @Override
    protected void log(String message, Throwable e) {
        if (e instanceof IOException && e.getMessage() != null && CLOSED_OR_RESET.contains(e.getMessage())) {
            log.warn(Failure.redact(message + " (Exception message: " + e.getMessage() + ")", token));
        } else {
            log.error(Failure.redact(message, token), Failure.redacted(e, token));
        }
    }
}
