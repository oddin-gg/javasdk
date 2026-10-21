package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The smallest feed worth running: one session that takes every message, the health of the SDK
 * logged every half minute, and a clean close.
 *
 * <p>Run with {@code ODDIN_GG_ODDS_FEED_TOKEN} set (see {@link ExampleConfig}); stop with Ctrl-C.
 */
public final class Quickstart {

    private static final Logger LOG = LoggerFactory.getLogger(Quickstart.class);

    private Quickstart() {}

    public static void main(String[] args) throws InterruptedException {
        // To see every call the SDK makes to the API, set setApiCallLogging(true) on the
        // configuration builder (see ExampleConfig): one INFO line per attempt, retries included,
        // with its method, URI, status and latency, under the logger com.oddin.oddsfeedsdk.api-calls
        // (docs/health-and-logging.md shows how to send it to a file of its own). That log is
        // complete. The global listener's onApiCall, which LoggingGlobalListener logs at DEBUG,
        // hears the same attempts for metrics, but drops the oldest when the listener falls behind.
        // The access token travels in a header, so it is in neither.
        //
        // Creating the feed does nothing yet: it starts on the first call of a manager getter or of
        // getSessionBuilder(), and that call can take the startup timeout (90 s) and throw.
        var feed = new OddsFeed(new LoggingGlobalListener(), ExampleConfig.fromEnvironment());

        // close() can be called more than once and from any thread: Ctrl-C closes through the hook, a
        // failure through the finally. Without it a feed that is never closed keeps the JVM running.
        Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "close-feed"));
        try {
            // Build every session before open(): after it, build() throws.
            feed.getSessionBuilder()
                    .setMessageInterest(MessageInterest.ALL)
                    .setListener(new LoggingListener())
                    .build();
            feed.open();

            while (true) {
                Thread.sleep(30_000);
                // Read on this thread, without waiting for the feed; counters() is a flat map
                // to hand to a metrics exporter.
                var health = feed.getHealth();
                LOG.info("health {} {}", health.state(), health.components());
                LOG.debug("counters {}", health.counters());
            }
        } finally {
            feed.close();
        }
    }
}
