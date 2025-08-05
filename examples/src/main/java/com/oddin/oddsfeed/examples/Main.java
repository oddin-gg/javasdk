package com.oddin.oddsfeed.examples;


import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.ProducerManager;
import com.oddin.oddsfeedsdk.api.MarketDescriptionManager;
import com.oddin.oddsfeedsdk.api.SportsInfoManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.*;
import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.config.Region;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.*;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// Very basic example of receiving data from odds feed and printing them to console
public class Main {

    public static void main(String[] args) {
        String token = System.getenv("ODDIN_GG_ODDS_FEED_TOKEN");
        if (token == null) {
            // @TODO Set your access token here or fill environment property ODDIN_GG_ODDS_FEED_TOKEN
            token = "change it here or setup ODDIN_GG_ODDS_FEED_TOKEN env property";
        }

        String env = System.getenv("ENVIRONMENT");

        // Basic configuration for odds feed.
        // You need to properly set your access token in order to access API and Feed features.
        // Please check with support that your IP was whitelisted.
        OddsFeedConfiguration configuration;

        if ("test".equalsIgnoreCase(env)) {
            configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectTest()
                    .setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH)
                    .setAccessToken(token)
                    .setSDKNodeId(1)
                    .setInitialSnapshotRecoveryInterval(Duration.ofMinutes(5))
                    .build();
        } else {
            configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectIntegration(Region.DEFAULT)
                    .setExceptionHandlingStrategy(ExceptionHandlingStrategy.CATCH)
                    .setAccessToken(token)
                    .setSDKNodeId(1)
                    .setInitialSnapshotRecoveryInterval(Duration.ofMinutes(5))
                    .build();
        }

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
                        SportEvent e = message.getEvent();

                        if (e instanceof  Match) {
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

                        if (e instanceof Tournament) {
                            Tournament tournament = (Tournament) message.getEvent();
                            System.out.println("OddsChange event on tournemant: " + tournament.getId() +" " + tournament.getName(Locale.getDefault()));
                        }
                    }

                    @Override
                    public void onBetStop(@NotNull OddsFeedSession session, @NotNull BetStop<SportEvent> message) {
                        System.out.println("Bet stop message received " + message);
                    }

                    @Override
                    public void onBetSettlement(@NotNull OddsFeedSession session, @NotNull BetSettlement<SportEvent> message) {
                        System.out.println("Bet settlement message received " + message);
                        message.getMarkets().forEach(m -> m.getOutcomeSettlements().forEach(o -> {
                            if(o.getVoidFactor() != null) {
                                System.out.println("Received outcome with void factor: " + o.getVoidFactor());
                            }
                        }));
                    }

                    @Override
                    public void onRollbackBetSettlement(@NotNull OddsFeedSession session, @NotNull RollbackBetSettlement<SportEvent> message) {
                        System.out.println("Rollback Settlement received for match: " + message.getEvent().getName(Locale.ENGLISH));

                        List<Market> markets = message.getMarkets();
                        markets.forEach(m -> System.out.println("     Rollback bet settlement message received for market type " + m.getId() + ": " + m.getName() + ", " + m.getSpecifiers()));
                    }

                    @Override
                    public void onRollbackBetCancel(@NotNull OddsFeedSession session, @NotNull RollbackBetCancel<SportEvent> message) {
                        System.out.println("Rollback Cancel received for match: " + message.getEvent().getName(Locale.ENGLISH));

                        List<Market> markets = message.getMarkets();
                        markets.forEach(m -> System.out.println("     Rollback bet cancel message received for market type " + m.getId() + ": " + m.getName() + ", " + m.getSpecifiers()));
                    }

                    @Override
                    public void onBetCancel(@NotNull OddsFeedSession session, @NotNull BetCancel<SportEvent> message) {
                        System.out.println("Bet cancel message received " + message);
                        for (MarketCancel market : message.getMarkets()) {
                            System.out.println("Canceled market: '"+market.getName()+"'; VoidReasonID: "+market.getVoidReasonId()+"; VoidReasonParams: '"+market.getVoidReasonParams()+"'");
                        }
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
        for (MarketDescription md : Objects.requireNonNull(marketManager.getMarketDescriptions())) {
            System.out.println(
                    "Market: " + md.getId() + "; "
                            + md.getName(Locale.getDefault()) + "; "
                            + md.getIncludesOutcomesOfType() + "; "
                            + md.getOutcomeType() + "; "
                            + md.getVariant() + "; "
                            + md.getSpecifiers() + "; "
                            + md.getOutcomes() + "; "
            );
        }

        List<MarketVoidReason> voidReasons = marketManager.getMarketVoidReasons();
        if(voidReasons != null) {
            for (MarketVoidReason voidReason : voidReasons) {
                System.out.println("Void reason: " + voidReason.getId() + "; '" +
                        voidReason.getName() + "'; '"
                        + voidReason.getDescription() + "'; '"
                        + voidReason.getTemplate() + "'; '"
                        + voidReason.getParams() + "'; '"
                );
            }
        }

        // Sports info manager gives you information about all sports, tournaments, matches and fixtures
        SportsInfoManager sportsInfoManager = oddsFeed.getSportsInfoManager();

        // Feed needs to be opened to init session data
        oddsFeed.open();

        // Racing events
        Match race = sportsInfoManager.getMatch(URN.parse("od:match:6516"), Locale.getDefault());
        System.out.println("Hello to race " + race.getName(Locale.getDefault()));
        System.out.println("Sport format: " + race.getSportFormat());
        System.out.println("Extra info: "     + race.getExtraInfo());
        System.out.println("Fixture extra info: "     + race.getFixture().getExtraInfo());


        List<Competitor> competitors = race.getCompetitors();
        for (Competitor competitor : competitors) {
            System.out.println("Hello to competitor " + competitor.getName(Locale.getDefault()));
        }

        Player player = sportsInfoManager.getPlayer(URN.parse("od:player:111"), Locale.getDefault());
        assert player != null;
        System.out.println("Hello to player " + player.getFullName(Locale.getDefault()));

        Competitor competitor = sportsInfoManager.getCompetitor(URN.parse("od:competitor:300"));
        System.out.println("Competitor Players:");
        if (competitor != null) {
            List<Player> competitorPlayers = competitor.getPlayers();
            if (competitorPlayers != null) {
                for (Player competitorPlayer : competitorPlayers) {
                    System.out.println("    Localized name: " + competitorPlayer.getName(Locale.getDefault()));
                    System.out.println("    Sport ID: " + competitorPlayer.getSportID(Locale.getDefault()));
                }
            }
        }

        List<Match> listOfMatches = sportsInfoManager.getListOfMatches(0, 2, Locale.getDefault());
        if (listOfMatches != null && !listOfMatches.isEmpty()) {
            Match match = listOfMatches.get(0);
            Competitor homeCompetitor = match.getHomeCompetitor();
            System.out.println("Home players:");
            if (homeCompetitor != null) {
                List<Player> homePlayers = homeCompetitor.getPlayers();
                if (homePlayers != null) {
                    for (Player competitorPlayer : homePlayers) {
                        System.out.println("    Localized name: " + competitorPlayer.getName(Locale.getDefault()));
                        System.out.println("    Sport ID: " + competitorPlayer.getSportID(Locale.getDefault()));
                    }
                }
            }
        }

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
