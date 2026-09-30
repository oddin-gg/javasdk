package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.ReplayManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
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
            assertThat(replay.getReplayList())
                    .as("the replay list")
                    .extracting(SportEvent::getId)
                    .containsExactly(MATCH);
            assertThat(replay.play()).as("playing").isTrue();
            assertThat(rest.requests())
                    .extracting(request -> request.method() + " " + request.path())
                    .as("what the replay manager asked the API for")
                    .contains("PUT /v1/replay/events/" + MATCH, "GET /v1/replay", "POST /v1/replay/play");

            feed.publishFixture(BET_STOP);
            assertThat(feed.publishReplay(Fixtures.read(ODDS_CHANGE)))
                    .as("routed to the replay session")
                    .isTrue();
            var first = replayed.next(Message.class);
            assertThat(first)
                    .as("the first message the replay session gets - not the live bet stop")
                    .isInstanceOf(OddsChange.class);
            assertThat(((OddsChange<?>) first).getEvent().getId())
                    .as("its event")
                    .isEqualTo(MATCH);

            assertThat(replay.stop()).as("stopping").isTrue();
            assertThat(replay.clear()).as("clearing").isTrue();
            assertThat(rest.requests())
                    .extracting(request -> request.method() + " " + request.path())
                    .as("what the replay manager asked the API for")
                    .contains("POST /v1/replay/stop", "POST /v1/replay/clear");
        }
    }

    /**
     * A replay session cannot open next to a live one: it gets the interest "all", which is allowed
     * only for a feed with one session. Replay runs on a feed of its own, in 0.0.x and in 1.0 alike,
     * so replayed state never shares caches with live state.
     */
    @Test
    void aReplaySessionCannotOpenNextToALiveSession() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            var oddsFeed = sdk.oddsFeed();
            oddsFeed.getSessionBuilder()
                    .setListener(new Received())
                    .setMessageInterest(MessageInterest.LIVE_ONLY)
                    .build();
            oddsFeed.getSessionBuilder().setListener(new Received()).buildReplay();

            assertThat(catchThrowable(oddsFeed::open))
                    .as("opening a live and a replay session")
                    .isInstanceOf(UnsupportedMessageInterestCombination.class)
                    .hasMessageContaining("all messages can be used only for single session configuration");
        }
    }
}
