package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.Fixtures;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.ReplayManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import org.junit.jupiter.api.Test;

/**
 * Replay: the replay manager drives the replay over REST, and a replay session receives what is
 * played on the replay exchange - and nothing from the live feed.
 */
class ReplayScenarioIT {

  private static final URN MATCH = URN.parse("od:match:198314");
  private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
  private static final String BET_STOP = "feed/bet_stop/bet_stop_all_groups.xml";

  @Test
  void aReplaySessionReceivesWhatTheReplayPlays() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Received replayed = sdk.openReplay();
      ReplayManager replay = sdk.oddsFeed().getReplayManager();

      assertThat(replay.addSportEvent(MATCH)).as("adding the match").isTrue();
      assertThat(replay.getReplayList()).as("the replay list").extracting(SportEvent::getId).containsExactly(MATCH);
      assertThat(replay.play()).as("playing").isTrue();
      assertThat(rest.requests()).extracting(request -> request.method() + " " + request.path())
          .as("what the replay manager asked the API for")
          .contains("PUT /v1/replay/events/" + MATCH, "GET /v1/replay", "POST /v1/replay/play");

      feed.publishFixture(BET_STOP);
      assertThat(feed.publishReplay(Fixtures.read(ODDS_CHANGE))).as("routed to the replay session").isTrue();
      var first = replayed.next(Message.class);
      assertThat(first).as("the first message the replay session gets - not the live bet stop")
          .isInstanceOf(OddsChange.class);
      assertThat(((OddsChange<?>) first).getEvent().getId()).as("its event").isEqualTo(MATCH);

      assertThat(replay.stop()).as("stopping").isTrue();
      assertThat(replay.clear()).as("clearing").isTrue();
      assertThat(rest.requests()).extracting(request -> request.method() + " " + request.path())
          .as("what the replay manager asked the API for")
          .contains("POST /v1/replay/stop", "POST /v1/replay/clear");
    }
  }

  /**
   * A replay session next to a live one. 0.0.x refuses the combination when the feed opens: it
   * gives every replay session the interest "all", which it allows only for a feed with one
   * session. 1.0 opens both, each gets its own messages, and what is replayed does not reach the
   * live side's caches - the replayed score does not become the live match's.
   */
  @Test
  void aReplaySessionOpensNextToALiveSession() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      var live = new Received();
      var replayed = new Received();
      var oddsFeed = sdk.oddsFeed();
      oddsFeed.getSessionBuilder().setListener(live).setMessageInterest(MessageInterest.LIVE_ONLY).build();
      oddsFeed.getSessionBuilder().setListener(replayed).buildReplay();

      KnownDifference.REPLAY_NEXT_TO_LIVE_IS_REFUSED.expect(
          () -> assertThat(catchThrowable(oddsFeed::open)).as("opening a live and a replay session")
              .isInstanceOf(UnsupportedMessageInterestCombination.class)
              .hasMessageContaining("all messages can be used only for single session configuration"),
          () -> {
            oddsFeed.open();
            // the summary the live side loads says 3; the replay says 9
            feed.publishReplay(Fixtures.replace(
                Fixtures.read("feed/odds_change/odds_change_closed_with_winner.xml"),
                "home_score=\"3\"", "home_score=\"9\""));
            assertThat(replayed.next(Message.class)).as("what the replay session gets").isInstanceOf(OddsChange.class);
            feed.publishFixture(BET_STOP);
            assertThat(live.next(Message.class)).as("what the live session gets").isInstanceOf(BetStop.class);
            assertThat(oddsFeed.getSportsInfoManager().getMatch(MATCH).getStatus().getHomeScore())
                .as("home score of the live match").isEqualTo(3.0);
          });
    }
  }
}
