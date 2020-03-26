package com.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.ReplayManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.*;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import org.jetbrains.annotations.NotNull;

public class Replay {
    private final OddsFeed oddsFeed;

    public Replay(String token) {
        OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                // Only supported env for now is production
                .selectProduction()
                .setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH)
                .setAccessToken(token)
                .setSDKNodeId(1)
                .build();

        oddsFeed = new OddsFeed(new GlobalEventsListener() {
            @Override
            public void onProducerStatusChange(@NotNull ProducerStatus producerStatus) {
            }

            @Override
            public void onConnectionDown() {
            }

            @Override
            public void onEventRecoveryCompleted(@NotNull URN eventId, long requestId) {
            }
        }, configuration);
    }

    public void run() {
        oddsFeed.getSessionBuilder()
                .setMessageInterest(MessageInterest.ALL)
                .setListener(new OddsFeedListener() {
                    @Override
                    public void onOddsChange(@NotNull OddsFeedSession oddsFeedSession, @NotNull OddsChange<SportEvent> oddsChange) {

                    }

                    @Override
                    public void onBetStop(@NotNull OddsFeedSession oddsFeedSession, @NotNull BetStop<SportEvent> betStop) {

                    }

                    @Override
                    public void onBetSettlement(@NotNull OddsFeedSession oddsFeedSession, @NotNull BetSettlement<SportEvent> betSettlement) {

                    }

                    @Override
                    public void onBetCancel(@NotNull OddsFeedSession oddsFeedSession, @NotNull BetCancel<SportEvent> betCancel) {

                    }

                    @Override
                    public void onFixtureChange(@NotNull OddsFeedSession oddsFeedSession, @NotNull FixtureChange<SportEvent> fixtureChange) {

                    }

                    @Override
                    public void onUnparsableMessage(@NotNull OddsFeedSession oddsFeedSession, @NotNull UnparsableMessage<SportEvent> unparsableMessage) {

                    }
                })
                .buildReplay();

        oddsFeed.open();

        ReplayManager replayManager = oddsFeed.getReplayManager();

        // Create test URN
        // You can also obtain list of matches view SportsInfoManager
        URN lolMatch = URN.parse("od:match:1942");
        URN dota2Match = URN.parse("od:match:3111");
        URN csgoMatch = URN.parse("od:match:2220");
        URN fifaMatch = URN.parse("od:match:653");
        URN overwatchMatch = URN.parse("od:match:2952");
        URN kogMatch = URN.parse("od:match:3073");
        URN sc2Match = URN.parse("od:match:2981");
        URN rocketLeagueMatch = URN.parse("od:match:2997");

        // Add test events to replay manager
        replayManager.addSportEvent(lolMatch);
        replayManager.addSportEvent(dota2Match);
        replayManager.addSportEvent(csgoMatch);
        replayManager.addSportEvent(fifaMatch);
        replayManager.addSportEvent(overwatchMatch);
        replayManager.addSportEvent(kogMatch);
        replayManager.addSportEvent(sc2Match);
        replayManager.addSportEvent(rocketLeagueMatch);

        // Start replay with 30x speed and max delay 500ms
        replayManager.play(30, 500);

        // You will receive messages on your listener
        try {
            Thread.sleep(1000 * 60 * 30);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        oddsFeed.close();
    }
}
