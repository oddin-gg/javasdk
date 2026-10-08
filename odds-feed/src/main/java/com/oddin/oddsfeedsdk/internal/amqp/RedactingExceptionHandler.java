package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.internal.log.Throttle;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.impl.ForgivingExceptionHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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

        void debug(String message, @Nullable Throwable e);
    }

    /** What the forgiving handler takes for a socket closed or reset. */
    private static final Set<String> CLOSED_OR_RESET =
            Set.of("Connection reset", "Socket closed", "Connection reset by peer");

    private static final Logger LOG = LoggerFactory.getLogger(AmqpTransport.class);
    /** How often a socket closed or reset is a warning: the first, then at most once in this long. */
    private static final Duration CLOSED_OR_RESET_INTERVAL = Duration.ofMinutes(1);

    private final String token;
    private final Log log;
    private final Throttle closedOrReset = new Throttle();
    private final Throttle callbackFailures = new Throttle();

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

            @Override
            public void debug(String message, @Nullable Throwable e) {
                LOG.debug(message, e);
            }
        });
    }

    RedactingExceptionHandler(String token, Log log) {
        this.token = token;
        this.log = log;
    }

    @Override
    protected void log(String message, Throwable e) {
        if (isClosedOrReset(e)) {
            String line = Failure.redact(message + " (Exception message: " + e.getMessage() + ")", token);
            if (closedOrReset.dueEvery(System.nanoTime(), CLOSED_OR_RESET_INTERVAL)) {
                log.warn(line);
            } else {
                log.debug(line, null);
            }
        } else {
            log.error(Failure.redact(message, token), Failure.redacted(e, token));
        }
    }

    /**
     * A failure of a client's callback - a consumer, a return or a confirm listener - repeats per message,
     * so the first is an error and then one in a thousand, with its stack, and the rest are debug. The
     * handler's other reports - the connection driver, the recoveries - keep their own errors.
     */
    @Override
    protected void handleChannelKiller(Channel channel, Throwable e, String what) {
        if (isClosedOrReset(e)) {
            super.handleChannelKiller(channel, e, what);
            return;
        }
        var line = Failure.redact(what + " threw an exception for channel " + channel, token);
        if (Throttle.due(callbackFailures.count())) {
            log.error(line, Failure.redacted(e, token));
        } else {
            log.debug(line, Failure.redacted(e, token));
        }
    }

    private static boolean isClosedOrReset(Throwable e) {
        return e instanceof IOException && e.getMessage() != null && CLOSED_OR_RESET.contains(e.getMessage());
    }
}
