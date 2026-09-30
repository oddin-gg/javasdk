package com.oddin.oddsfeedsdk.internal.amqp;

import com.rabbitmq.client.impl.ForgivingExceptionHandler;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's reports of what failed in its callbacks, logged with the access token taken out: the
 * client names a channel by its connection, and a connection by its user, which is the token. Like
 * the forgiving handler it extends, and unlike the client's default, it closes nothing - reconnecting
 * and reopening channels is the transport's.
 */
final class RedactingExceptionHandler extends ForgivingExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpTransport.class);

    private final String token;
    private final BiConsumer<String, Throwable> sink;

    RedactingExceptionHandler(String token) {
        this(token, LOG::error);
    }

    RedactingExceptionHandler(String token, BiConsumer<String, Throwable> sink) {
        this.token = token;
        this.sink = sink;
    }

    @Override
    protected void log(String message, Throwable e) {
        sink.accept(Failure.redact(message, token), Failure.redacted(e, token));
    }
}
