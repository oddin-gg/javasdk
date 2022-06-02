package com.oddin.oddsfeed.examples;


import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.*;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

// Very basic example of receiving data from odds feed and printing them to console
public class Main {

    public static void main(String[] args) {
        String token = System.getenv("ODDIN_GG_ODDS_FEED_TOKEN");
        if (token == null) {
            // @TODO Set your access token here or fill environment property ODDIN_GG_ODDS_FEED_TOKEN
            token = "change it here or setup ODDIN_GG_ODDS_FEED_TOKEN env property";
        }

        // Basic configuration for odds feed.
        // You need to properly set your access token in order to access API and Feed features.
        // Please check with support that your IP was whitelisted.
        OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectIntegration()
                .setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH)
                .setAccessToken(token)
                .setSDKNodeId(1)
                .setInitialSnapshotRecoveryInterval(Duration.ofMinutes(5))
                .build();

        // Init instance of odds feed with global listener
        OddsFeed oddsFeed = new OddsFeed(new GlobalEventsListener() {
            @Override
            public void onProducerStatusChange(@NotNull ProducerStatus producerStatus) {
                System.out.println(producerStatus.getProducerStatusReason());
            }

            @Override
            public void onConnectionDown() {
                System.out.println("Connection down");
            }

            @Override
            public void onEventRecoveryCompleted(@NotNull URN eventId, long requestId) {
                System.out.println("Event recovery completed " + eventId);
            }
        }, configuration);

        // Get instance of session builder and build session with given message interest
        // (e.g. which messages you want to receive) and message listener
        oddsFeed.getSessionBuilder()
                .setMessageInterest(MessageInterest.ALL)
                .setListener(new OddsFeedListener() {
                    @Override
                    public void onUnparsableMessage(@NotNull OddsFeedSession session, @NotNull UnparsableMessage<SportEvent> message) {
                        System.out.println("Unparsable message received " + message);
                    }

                    @Override
                    public void onOddsChange(@NotNull OddsFeedSession session, @NotNull OddsChange<SportEvent> message) {
                        System.out.println("Odds change message received " + message);

                        // Scoreboard
                        Match match = (Match) message.getEvent();
                        if (
                                match.getStatus() != null &&
                                        match.getStatus().isScoreboardAvailable() &&
                                        match.getStatus().getScoreboard() != null
                        ) {
                            Scoreboard scoreboard = match.getStatus().getScoreboard();
                            System.out.println("Home Goals: " + scoreboard.getHomeGoals());
                            System.out.println("Away Goals: " + scoreboard.getAwayGoals());
                            System.out.println("Time: " + scoreboard.getTime());
                            System.out.println("Game Time: " + scoreboard.getGameTime());
                        }
                    }

                    @Override
                    public void onBetStop(@NotNull OddsFeedSession session, @NotNull BetStop<SportEvent> message) {
                        System.out.println("Bet stop message received " + message);
                    }

                    @Override
                    public void onBetSettlement(@NotNull OddsFeedSession session, @NotNull BetSettlement<SportEvent> message) {
                        System.out.println("Bet settlement message received " + message);
                    }

                    @Override
                    public void onBetCancel(@NotNull OddsFeedSession session, @NotNull BetCancel<SportEvent> message) {
                        System.out.println("Bet cancel message received " + message);
                    }

                    @Override
                    public void onFixtureChange(@NotNull OddsFeedSession session, @NotNull FixtureChange<SportEvent> message) {
                        System.out.println("Fixture change received " + message);
                    }
                })
                .build();

        // Producer manager can be used to get more information about producer states and possible recoveries
        ProducerManager producerManager = oddsFeed.getProducerManager();

        // Market description manager gives you information about all markets and outcomes
        MarketDescriptionManager marketManager = oddsFeed.getMarketDescriptionManager();

        // Sports info manager gives you information about all sports, tournaments, matches and fixtures
        SportsInfoManager sportsInfoManager = oddsFeed.getSportsInfoManager();

        // Feed needs to be opened to init session data
        oddsFeed.open();

        try {
            // Sleep for 30 minutes
            Thread.sleep(1000 * 30 * 60);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Close feed instance to properly release all resources
        oddsFeed.close();
    }
}
