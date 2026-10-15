package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays recorded events back through a session. A replay session is the only session of its feed
 * and runs no recovery, so this feed does nothing else.
 *
 * <p>Arguments: the events' URNs, for example {@code od:match:1942 od:match:3111}; two matches
 * without them. Fixture changes of a replayed event reach the listener once per feed per hour, so
 * to see them again run the example again, with a new feed.
 */
public final class Replay {

    private static final Logger LOG = LoggerFactory.getLogger(Replay.class);

    private Replay() {}

    public static void main(String[] args) throws InterruptedException {
        var events = Arrays.stream(args.length > 0 ? args : new String[] {"od:match:1942", "od:match:3111"})
                .map(URN::parse)
                .toList();

        var feed = new OddsFeed(new LoggingGlobalListener(), ExampleConfig.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(feed::close, "close-feed"));
        try {
            // buildReplay() instead of build(), and before open()
            feed.getSessionBuilder().setListener(new LoggingListener()).buildReplay();
            feed.open();

            var replay = feed.getReplayManager();
            events.forEach(replay::addSportEvent);

            // 30 times the speed, at most 500 ms between two messages
            replay.play(30, 500);
            LOG.info("replay status: {}", replay.getReplayStatus());

            // The messages arrive on the listener; stop with Ctrl-C.
            while (true) {
                Thread.sleep(30_000);
                LOG.info("replay status: {}", replay.getReplayStatus());
            }
        } finally {
            feed.close();
        }
    }
}
