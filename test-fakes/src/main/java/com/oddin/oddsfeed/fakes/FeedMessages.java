package com.oddin.oddsfeed.fakes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Feed messages built from the vendored fixtures, changed only where a scenario needs it. */
public final class FeedMessages {

    private static final String ALIVE = "feed/alive/alive.xml";
    private static final String SNAPSHOT_COMPLETE = "feed/snapshot_complete/snapshot_complete.xml";
    static final Pattern ROOT_TIMESTAMP = Pattern.compile("(<[a-z_]+\\b[^>]*?\\btimestamp=\")\\d+(\")");

    private FeedMessages() {}

    /** An alive from this producer, subscribed or not. */
    public static String alive(int producer, boolean subscribed) {
        var alive = fromProducer(Fixtures.read(ALIVE), 1, producer);
        return Fixtures.replace(alive, "subscribed=\"1\"", "subscribed=\"" + (subscribed ? 1 : 0) + "\"");
    }

    /** The snapshot complete that ends this producer's recovery request. */
    public static String snapshotComplete(int producer, long requestId) {
        var complete = fromProducer(Fixtures.read(SNAPSHOT_COMPLETE), 1, producer);
        return Fixtures.replace(complete, "request_id=\"712\"", "request_id=\"" + requestId + "\"");
    }

    /** The message as this producer would send it, instead of the one the fixture names. */
    public static String fromProducer(String message, int fixtureProducer, int producer) {
        return Fixtures.replace(message, "product=\"" + fixtureProducer + "\"", "product=\"" + producer + "\"");
    }

    /**
     * The message stamped with this time; for {@link FakeFeed#publishAsIs}, which, unlike
     * {@link FakeFeed#publish(String)}, leaves the timestamp alone.
     */
    public static String stampedAt(String message, long timestamp) {
        Matcher root = ROOT_TIMESTAMP.matcher(message);
        if (!root.find()) {
            throw new IllegalArgumentException("no timestamp on the root element of " + message);
        }
        return message.substring(0, root.start())
                + root.group(1)
                + timestamp
                + root.group(2)
                + message.substring(root.end());
    }
}
