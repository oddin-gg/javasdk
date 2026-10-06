package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.util.function.UnaryOperator;

/**
 * The SDK under test, pointed at a fake REST API and a fake feed, and driven only through its
 * public API.
 *
 * <p>Meant as the last resource of a try-with-resources block, after the fakes, so it closes first
 * and a failure while closing is added to the test's own failure instead of replacing it. Closing
 * lets the SDK's background REST calls finish first (see {@link FakeRestServer#awaitQuiet}), and
 * closes the SDK even when they do not, so its reconnecting AMQP connection cannot outlive the
 * test.
 */
public final class Sdk implements AutoCloseable {

    /** The access token every test SDK uses; the fakes record it rather than check it. */
    public static final String TOKEN = "system-test-token";

    /**
     * Where an SDK without a fake feed is told the feed is: a port on this machine nothing listens
     * on, so a scenario that should never connect would fail loudly if it did.
     */
    private static final int NO_FEED_PORT = 1;

    private final OddsFeed oddsFeed;
    private final FakeRestServer rest;
    private final GlobalEvents events = new GlobalEvents();
    private boolean closed;

    private Sdk(
            FakeRestServer rest,
            String feedHost,
            int feedPort,
            UnaryOperator<OddsFeedConfigurationBuilder> configure,
            OddsFeedExtListener extListener) { // null for none
        OddsFeedConfiguration configuration = configure
                .apply(OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment(feedHost, rest.apiHost(), feedPort)
                        .setAccessToken(TOKEN))
                .build();
        this.oddsFeed = extListener == null
                ? new OddsFeed(events, configuration)
                : new OddsFeed(events, configuration, extListener);
        this.rest = rest;
    }

    public static Sdk against(FakeRestServer rest, FakeFeed feed) {
        return against(rest, feed, UnaryOperator.identity());
    }

    /** With configuration on top of the fakes' addresses and the token, e.g. an exception strategy. */
    public static Sdk against(
            FakeRestServer rest, FakeFeed feed, UnaryOperator<OddsFeedConfigurationBuilder> configure) {
        return new Sdk(rest, feed.host(), feed.port(), configure, null);
    }

    /**
     * For scenarios that only read through the managers and never open the feed, so no broker
     * needs to start.
     */
    public static Sdk withoutFeed(FakeRestServer rest, UnaryOperator<OddsFeedConfigurationBuilder> configure) {
        return new Sdk(rest, "127.0.0.1", NO_FEED_PORT, configure, null);
    }

    /** The same, with the SDK's extended listener, which hears the raw API data. */
    public static Sdk withoutFeed(FakeRestServer rest, OddsFeedExtListener extListener) {
        return new Sdk(rest, "127.0.0.1", NO_FEED_PORT, UnaryOperator.identity(), extListener);
    }

    public OddsFeed oddsFeed() {
        return oddsFeed;
    }

    public GlobalEvents events() {
        return events;
    }

    /** Builds one session with this interest and opens the feed; returns what the session receives. */
    public Received open(MessageInterest interest) {
        return open(interest, new Received());
    }

    /** Builds one session with this interest and listener, and opens the feed. */
    public Received open(MessageInterest interest, Received received) {
        oddsFeed.getSessionBuilder()
                .setListener(received)
                .setMessageInterest(interest)
                .build();
        oddsFeed.open();
        return received;
    }

    /** Builds a replay session, the only one, and opens the feed; returns what it receives. */
    public Received openReplay() {
        var received = new Received();
        oddsFeed.getSessionBuilder().setListener(received).buildReplay();
        oddsFeed.open();
        return received;
    }

    /** Closes the SDK once; a test may close it itself to check what closing leaves behind. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            rest.awaitQuiet();
        } finally {
            oddsFeed.close();
        }
    }
}
