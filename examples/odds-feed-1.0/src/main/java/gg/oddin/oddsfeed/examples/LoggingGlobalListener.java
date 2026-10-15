package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What concerns the feed as a whole, logged. Every callback runs on the feed's one events thread,
 * so a real client keeps them quick and hands slow work to its own executor.
 *
 * <p>The first three methods are the ones 0.0.x has; the rest are new in 1.0 and optional.
 */
class LoggingGlobalListener implements GlobalEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingGlobalListener.class);

    @Override
    public void onProducerStatusChange(ProducerStatus status) {
        // Down means messages may be missing; up means they have been recovered.
        LOG.info(
                "producer {}: {}, down={}",
                status.getProducer().getId(),
                status.getProducerStatusReason(),
                status.isDown());
    }

    @Override
    public void onConnectionDown() {
        LOG.warn("connection down");
    }

    @Override
    public void onEventRecoveryCompleted(URN eventId, long requestId) {
        LOG.info("event recovery {} of {} completed", requestId, eventId);
    }

    @Override
    public void onConnectionStateChange(ConnectionStateChange change) {
        LOG.info("connection {} {}", change.state(), change.reason());
    }

    /** A part of the SDK changed its health: {@code HEALTHY}, {@code DEGRADED} or {@code STALLED}. */
    @Override
    public void onHealthEvent(HealthEvent event) {
        LOG.info(
                "health of {}{}: {} -> {} ({})",
                event.component(),
                event.session() == null ? "" : " of a session",
                event.previous(),
                event.state(),
                event.reason());
    }

    /** The SDK gives up: a token the API refuses, a login the broker keeps refusing. Close and start anew. */
    @Override
    public void onFatalError(String reason, @Nullable Throwable cause) {
        LOG.error("fatal: {}", reason, cause);
    }
}
