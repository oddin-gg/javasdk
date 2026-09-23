package com.oddin.oddsfeed.systemtests.support;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.mq.MessageInterest;

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

  private final OddsFeed oddsFeed;
  private final FakeRestServer rest;
  private final GlobalEvents events = new GlobalEvents();
  private boolean closed;

  private Sdk(FakeRestServer rest, FakeFeed feed) {
    OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
        .selectEnvironment(feed.host(), rest.apiHost(), feed.port())
        .setAccessToken(TOKEN)
        .build();
    this.oddsFeed = new OddsFeed(events, configuration);
    this.rest = rest;
  }

  public static Sdk against(FakeRestServer rest, FakeFeed feed) {
    return new Sdk(rest, feed);
  }

  public OddsFeed oddsFeed() {
    return oddsFeed;
  }

  public GlobalEvents events() {
    return events;
  }

  /** Builds one session with this interest and opens the feed; returns what the session receives. */
  public Received open(MessageInterest interest) {
    Received received = new Received();
    oddsFeed.getSessionBuilder().setListener(received).setMessageInterest(interest).build();
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
