package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The first scenarios, through the public API only: open the feed, receive an odds change, read
 * the match it is about, close cleanly. Today they run against the old 0.0.x SDK; 1.0 has to pass
 * them unchanged.
 */
class OddsChangeScenarioIT {

  private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
  private static final URN MATCH = URN.parse("od:match:198314");

  @Test
  void anOddsChangeLeadsToTheMatchItIsAbout() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      // The SDK names competitors from their profiles, and the fake answers every competitor with
      // the same one - Team Alpha's. The away team needs its own.
      rest.respond("/v1/sports/en/competitors/od:competitor:47215/profile", 200,
          Fixtures.read("rest/competitor/competitor_profile_no_players.xml")
              .replace("od:competitor:47214", "od:competitor:47215")
              .replace("Team Alpha", "Team Beta"));
      Received received = sdk.open(MessageInterest.ALL);
      feed.publishFixture(ODDS_CHANGE);

      OddsChange<?> oddsChange = received.next(OddsChange.class);
      assertThat(oddsChange.getProducer().getId()).as("producer").isEqualTo(2);
      assertThat(oddsChange.getMarkets()).as("markets").hasSize(3);

      // the message carries only the event id; everything else the SDK fetches over REST
      assertThat(oddsChange.getEvent()).as("event").isInstanceOf(Match.class);
      Match match = (Match) oddsChange.getEvent();
      assertThat(match.getId()).as("match id").isEqualTo(MATCH);
      assertThat(match.getName(Locale.ENGLISH)).as("match name").isEqualTo("Team Alpha vs Team Beta");
      assertThat(match.getHomeCompetitor().getName(Locale.ENGLISH)).as("home").isEqualTo("Team Alpha");
      assertThat(match.getAwayCompetitor().getName(Locale.ENGLISH)).as("away").isEqualTo("Team Beta");
      assertThat(match.getTournament().getName(Locale.ENGLISH)).as("tournament").isEqualTo("Test Tournament");

      assertThat(rest.requests()).extracting(RecordedRequest::path)
          .as("the match came from its summary")
          .contains("/v1/sports/en/sport_events/" + MATCH + "/summary");
    }
  }

  @Test
  void closingTheFeedLeavesNothingBehind() throws InterruptedException {
    try (LogCapture logs = LogCapture.start();
        FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Set<Thread> before = liveNonDaemonThreads();
      Received received = sdk.open(MessageInterest.ALL);
      feed.publishFixture(ODDS_CHANGE);
      received.next(OddsChange.class);
      assertThat(feed.openConnections()).as("connected while open")
          .contains(new FakeFeed.Login(Sdk.TOKEN, feed.virtualHost()));

      sdk.close();

      assertThat(eventually(feed::openConnections, List::isEmpty))
          .as("connections left on the broker after close").isEmpty();
      assertThat(eventually(() -> newThreads(before), Set::isEmpty))
          .as("non-daemon threads left running after close - they would keep an application alive")
          .isEmpty();
      assertThat(logs.warningsFrom("com.oddin"))
          .as("what the SDK warned about from open to close").isEmpty();
    }
  }

  @Test
  void aMessageThatNeverArrivesFailsSayingWhatArrivedInstead() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Received received = sdk.open(MessageInterest.ALL);
      feed.publishFixture("feed/bet_stop/bet_stop_all_groups.xml");

      // a deliberately wrong expectation: the feed sent a bet stop, not an odds change
      assertThatThrownBy(() -> received.next(OddsChange.class, Duration.ofSeconds(3)))
          .isInstanceOf(AssertionError.class)
          .hasMessage("no OddsChange reached the listener within 3 s; "
              + "it got BetStop for od:match:198314 from producer 2 instead");
    }
  }

  private static Set<Thread> liveNonDaemonThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && !thread.isDaemon())
        .collect(Collectors.toSet());
  }

  private static Set<String> newThreads(Set<Thread> before) {
    return liveNonDaemonThreads().stream()
        .filter(thread -> !before.contains(thread))
        .map(Thread::getName)
        .collect(Collectors.toSet());
  }

  /** Polls for up to five seconds until the value passes the check; returns the last value either way. */
  private static <T> T eventually(java.util.function.Supplier<T> value, java.util.function.Predicate<T> done)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    T current = value.get();
    while (!done.test(current) && System.nanoTime() < deadline) {
      Thread.sleep(100);
      current = value.get();
    }
    return current;
  }
}
