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
    try (FakeRestServer rest = FakeRestServer.start(); FakeFeed feed = FakeFeed.start()) {
      Received received = new Received();
      OddsFeed sdk = sdkAgainst(rest, feed, new ConnectionEvents());
      try {
        sdk.getSessionBuilder().setListener(received).setMessageInterest(MessageInterest.ALL).build();
        sdk.open();

        assertThat(feed.publishFixture(ODDS_CHANGE)).as("routed to the SDK's queue").isTrue();

        OddsChange<SportEvent> oddsChange = received.next();
        assertThat(oddsChange).as("an odds change within " + DELIVERY).isNotNull();
        assertThat(oddsChange.getEvent().getId()).isEqualTo(URN.parse("od:match:198314"));
        assertThat(oddsChange.getProducer().getId()).isEqualTo(2);
        assertThat(oddsChange.getMarkets()).hasSize(3);
        assertThat(oddsChange.getTimestamp().getCreated())
            .as("the fake stamps messages with the time they are sent")
            .isCloseTo(System.currentTimeMillis(), within(60_000L));

        assertThat(feed.logins()).as("the SDK logs in with the token to the bookmaker's virtual host")
            .contains(new FakeFeed.Login(TOKEN, "/oddinfeed/" + FakeFeed.BOOKMAKER_ID));
      } finally {
        rest.awaitQuiet();
        sdk.close();
      }
    }
  }

  @Test
  void aPausedBrokerLooksLikeALostConnectionAndResumingRestoresIt() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start(); FakeFeed feed = FakeFeed.start()) {
      Received received = new Received();
      ConnectionEvents events = new ConnectionEvents();
      OddsFeed sdk = sdkAgainst(rest, feed, events);
      try {
        sdk.getSessionBuilder().setListener(received).setMessageInterest(MessageInterest.ALL).build();
        sdk.open();
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

        // the reconnect has logged in; its queue and bindings may be a moment behind
        deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!feed.publishFixture(ODDS_CHANGE) && System.nanoTime() < deadline) {
          Thread.sleep(200);
        }
        assertThat(received.next()).as("an odds change after the reconnect").isNotNull();
      } finally {
        rest.awaitQuiet();
        sdk.close();
      }
    }
  }

  private static OddsFeed sdkAgainst(FakeRestServer rest, FakeFeed feed, GlobalEventsListener events) {
    OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
        .selectEnvironment(feed.host(), rest.apiHost(), feed.port())
        .setAccessToken(TOKEN)
        .build();
    return new OddsFeed(events, configuration);
  }

  /** Collects odds changes; the other callbacks are not under test here. */
  private static final class Received implements OddsFeedListener {

    private final BlockingQueue<OddsChange<SportEvent>> oddsChanges = new LinkedBlockingQueue<>();

    OddsChange<SportEvent> next() throws InterruptedException {
      return oddsChanges.poll(DELIVERY.toMillis(), TimeUnit.MILLISECONDS);
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
