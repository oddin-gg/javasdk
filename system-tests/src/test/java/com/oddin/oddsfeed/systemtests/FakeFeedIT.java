package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The fake feed, driven through the SDK's public API: the SDK opens a session against the broker,
 * the fake publishes, and the message arrives at the SDK's listener.
 */
class FakeFeedIT {

  private static final String TOKEN = "system-test-token";
  private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
  private static final Duration DELIVERY = Duration.ofSeconds(10);

  @Test
  void theSdkReceivesAnOddsChangeFromTheFake() throws InterruptedException {
    Received received = new Received();
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed, new ConnectionEvents())) {
      sdk.feed().getSessionBuilder().setListener(received).setMessageInterest(MessageInterest.ALL).build();
      sdk.feed().open();

      assertThat(feed.publishFixture(ODDS_CHANGE)).as("routed to the SDK's queue").isTrue();

      OddsChange<SportEvent> oddsChange = received.next(DELIVERY);
      assertThat(oddsChange).as("an odds change within " + DELIVERY).isNotNull();
      assertThat(oddsChange.getEvent().getId()).isEqualTo(URN.parse("od:match:198314"));
      assertThat(oddsChange.getProducer().getId()).isEqualTo(2);
      assertThat(oddsChange.getMarkets()).hasSize(3);
      assertThat(oddsChange.getTimestamp().getCreated())
          .as("the fake stamps messages with the time they are sent")
          .isCloseTo(System.currentTimeMillis(), within(60_000L));

      assertThat(feed.logins()).as("the SDK logs in with the token to the bookmaker's virtual host")
          .contains(new FakeFeed.Login(TOKEN, "/oddinfeed/" + FakeFeed.BOOKMAKER_ID));
    }
  }

  @Test
  void aPausedBrokerLooksLikeALostConnectionAndResumingRestoresIt() throws InterruptedException {
    Received received = new Received();
    ConnectionEvents events = new ConnectionEvents();
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed, events)) {
      sdk.feed().getSessionBuilder().setListener(received).setMessageInterest(MessageInterest.ALL).build();
      sdk.feed().open();
      int loginsBefore = feed.logins().size();

      feed.pause();
      assertThat(events.down.await(30, TimeUnit.SECONDS))
          .as("the SDK reports the connection down while the broker is paused").isTrue();

      feed.resume();
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (feed.logins().size() == loginsBefore && System.nanoTime() < deadline) {
        Thread.sleep(200);
      }
      assertThat(feed.logins()).as("the SDK logs in again once the broker is back")
          .hasSizeGreaterThan(loginsBefore);

      // Logging in comes before the new queue is bound, and until the broker notices the old
      // connection is gone its queue still takes messages - so "routed" proves nothing yet. Keep
      // publishing until one actually arrives.
      OddsChange<SportEvent> afterReconnect = null;
      deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (afterReconnect == null && System.nanoTime() < deadline) {
        feed.publishFixture(ODDS_CHANGE);
        afterReconnect = received.next(Duration.ofSeconds(1));
      }
      assertThat(afterReconnect).as("an odds change after the reconnect").isNotNull();
    }
  }

  /**
   * The SDK under test, closed as a try-with-resources resource so that a failure while closing
   * is added to the test's own failure instead of replacing it. Background REST calls are let
   * finish first (see {@link FakeRestServer#awaitQuiet}), and the SDK is closed even when they
   * do not, so its reconnecting AMQP connection cannot outlive the test.
   */
  private record Sdk(OddsFeed feed, FakeRestServer rest) implements AutoCloseable {

    static Sdk against(FakeRestServer rest, FakeFeed broker, GlobalEventsListener events) {
      OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
          .selectEnvironment(broker.host(), rest.apiHost(), broker.port())
          .setAccessToken(TOKEN)
          .build();
      return new Sdk(new OddsFeed(events, configuration), rest);
    }

    @Override
    public void close() {
      try {
        rest.awaitQuiet();
      } finally {
        feed.close();
      }
    }
  }

  /** Collects odds changes; the other callbacks are not under test here. */
  private static final class Received implements OddsFeedListener {

    private final BlockingQueue<OddsChange<SportEvent>> oddsChanges = new LinkedBlockingQueue<>();

    OddsChange<SportEvent> next(Duration wait) throws InterruptedException {
      return oddsChanges.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
      oddsChanges.add(message);
    }

    @Override
    public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {}

    @Override
    public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {}

    @Override
    public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {}

    @Override
    public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {}

    @Override
    public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {}

    @Override
    public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {}

    @Override
    public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {}
  }

  private static final class ConnectionEvents implements GlobalEventsListener {

    final CountDownLatch down = new CountDownLatch(1);

    @Override
    public void onProducerStatusChange(ProducerStatus producerStatus) {}

    @Override
    public void onConnectionDown() {
      down.countDown();
    }

    @Override
    public void onEventRecoveryCompleted(URN eventId, long requestId) {}
  }
}
