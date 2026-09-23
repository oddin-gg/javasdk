package com.oddin.oddsfeed.systemtests.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Keeps what is logged while it is open, for a test to check what the SDK complained about. */
public final class LogCapture implements AutoCloseable {

  private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private LogCapture() {
    appender.start();
    root.addAppender(appender);
  }

  public static LogCapture start() {
    return new LogCapture();
  }

  /** "LEVEL logger: message" for everything at WARN or above from loggers under this prefix. */
  public List<String> warningsFrom(String loggerPrefix) {
    synchronized (appender.list) {
      return appender.list.stream()
          .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
          .filter(event -> event.getLoggerName().startsWith(loggerPrefix))
          .map(event -> event.getLevel() + " " + event.getLoggerName() + ": " + event.getFormattedMessage())
          .toList();
    }
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    appender.stop();
  }
}
