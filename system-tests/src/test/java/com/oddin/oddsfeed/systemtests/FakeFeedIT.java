package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The fake feed, driven through the SDK's public API: the SDK opens a session against the broker,
 * the fake publishes, and the message arrives at the SDK's listener.
 */
class FakeFeedIT {

  private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";

  @Test
  void theSdkReceivesAnOddsChangeFromTheFake() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Received received = sdk.open(MessageInterest.ALL);

      assertThat(feed.publishFixture(ODDS_CHANGE)).as("routed to the SDK's queue").isTrue();

      OddsChange<?> oddsChange = received.next(OddsChange.class);
      assertThat(oddsChange.getEvent().getId()).isEqualTo(URN.parse("od:match:198314"));
      assertThat(oddsChange.getProducer().getId()).isEqualTo(2);
      assertThat(oddsChange.getMarkets()).hasSize(3);
      assertThat(oddsChange.getTimestamp().getCreated())
          .as("the fake stamps messages with the time they are sent")
          .isCloseTo(System.currentTimeMillis(), within(60_000L));

      assertThat(feed.logins()).as("the SDK logs in with the token to the bookmaker's virtual host")
          .contains(new FakeFeed.Login(Sdk.TOKEN, "/oddinfeed/" + FakeFeed.BOOKMAKER_ID));
    }
  }

  @Test
  void aPausedBrokerLooksLikeALostConnectionAndResumingRestoresIt() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Received received = sdk.open(MessageInterest.ALL);
      int loginsBefore = feed.logins().size();

      feed.pause();
      assertThat(sdk.events().awaitConnectionDown(Duration.ofSeconds(30)))
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
      Optional<OddsChange> afterReconnect = Optional.empty();
      deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (afterReconnect.isEmpty() && System.nanoTime() < deadline) {
        feed.publishFixture(ODDS_CHANGE);
        afterReconnect = received.poll(OddsChange.class, Duration.ofSeconds(1));
      }
      assertThat(afterReconnect).as("an odds change after the reconnect").isPresent();
    }
  }
}
