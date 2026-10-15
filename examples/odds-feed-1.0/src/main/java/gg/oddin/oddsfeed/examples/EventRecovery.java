package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks for the messages of one event again, and follows the request. The messages arrive on the
 * session like any others; {@code onEventRecoveryCompleted} tells the request is done, and
 * {@code getEventRecoveryStatus} says where it is: {@code PENDING}, {@code COMPLETED}, {@code FAILED}
 * or {@code TIMED_OUT}.
 *
 * <p>Arguments: the producer's id and the event's URN, for example {@code 1 od:match:1234}.
 */
public final class EventRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(EventRecovery.class);

    private EventRecovery() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 2) {
            System.err.println("usage: EventRecovery <producer id> <event urn, such as od:match:1234>");
            return;
        }
        long producerId = Long.parseLong(args[0]);
        var eventId = URN.parse(args[1]);

        var feed = new OddsFeed(new LoggingGlobalListener(), ExampleConfig.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "close-feed"));
        try {
            feed.getSessionBuilder()
                    .setMessageInterest(MessageInterest.ALL)
                    .setListener(new LoggingListener())
                    .build();
            feed.open();

            // null: not accepted. The feed is not open, the connection is down, 128 recoveries of the
            // producer are in flight already, or this is a replay feed.
            // initiateEventStatefulMessagesRecovery asks for the stateful messages instead.
            var recovery = feed.getRecoveryManager();
            var requestId = recovery.initiateEventOddsMessagesRecovery(producerId, eventId);
            if (requestId == null) {
                LOG.error("the recovery of {} was not accepted", eventId);
                return;
            }

            // An ended recovery stays readable for five minutes.
            var giveUp = Instant.now().plus(Duration.ofMinutes(5));
            while (Instant.now().isBefore(giveUp)) {
                var status = recovery.getEventRecoveryStatus(requestId);
                LOG.info("recovery {}: {}", requestId, status == null ? "unknown" : status.state());
                if (status != null && status.state() != EventRecoveryStatus.State.PENDING) {
                    LOG.info("ended: {}", status);
                    return;
                }
                Thread.sleep(2_000);
            }
        } finally {
            feed.close();
        }
    }
}
