package com.oddin.oddsfeedsdk;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * What one class of the SDK logs at WARN or above, or at the level asked for, while it is open, for a
 * test to check what the client would read; the unit tests log nothing otherwise.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> source, Level level) {
        logger = (Logger) LoggerFactory.getLogger(source);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source, Level.WARN);
    }

    /** What the class logs at {@code level} or above. */
    public static LogCapture of(Class<?> source, Level level) {
        return new LogCapture(source, level);
    }

    /** "LEVEL message" for each line, oldest first. */
    public List<String> lines() {
        // AppenderBase.doAppend adds to the list holding the appender's own lock
        synchronized (appender) {
            return appender.list.stream()
                    .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                    .toList();
        }
    }

    /** The exception each line carries, with its causes and stack, or an empty string for a line without one. */
    public List<String> stacks() {
        synchronized (appender) {
            return appender.list.stream()
                    .map(event -> event.getThrowableProxy() == null
                            ? ""
                            : ThrowableProxyUtil.asString(event.getThrowableProxy()))
                    .toList();
        }
    }

    @Override
    public void close() {
        logger.setLevel(null);
        logger.detachAppender(appender);
        appender.stop();
    }
}
