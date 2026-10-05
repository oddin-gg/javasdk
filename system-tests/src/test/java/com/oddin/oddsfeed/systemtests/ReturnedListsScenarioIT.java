package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Competitor;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Sport;
import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The lists and maps the getters return are the client's to change: sorted, added to, cleared.
 * 0.0.x returned mutable ones, so client code that sorts what it got keeps working. 1.0 returns a
 * new one on every call; 0.0.x handed out what it cached, so the scenario does not read them back.
 */
class ReturnedListsScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN HOME = URN.parse("od:competitor:47214");

    @Test
    void theListsTheGettersReturnCanBeChanged() {
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, UnaryOperator.identity())) {
            var sportsInfo = sdk.oddsFeed().getSportsInfoManager();
            var markets = sdk.oddsFeed().getMarketDescriptionManager();

            List<Sport> sports = sportsInfo.getSports();
            sports.sort(Comparator.comparing((Sport sport) -> String.valueOf(sport.getId()))
                    .reversed());
            assertThat(sports).as("the sports, sorted").hasSize(2);
            sportsInfo.getActiveTournaments().clear();
            sportsInfo.getLiveMatches().removeIf(match -> MATCH.equals(match.getId()));

            Match match = sportsInfo.getMatch(MATCH);
            List<Competitor> competitors = match.getCompetitors();
            competitors.add(competitors.get(0));
            assertThat(competitors).as("the competitors, added to").hasSize(3);
            // 0.0.x sorted these into a list of fixed size: it can be sorted, not added to
            match.getStatus()
                    .getPeriodScores()
                    .sort(Comparator.comparingInt(PeriodScore::getPeriodNumber).reversed());
            match.getFixture().getTvChannels().clear();

            Competitor home = sportsInfo.getCompetitor(HOME);
            home.getPlayers().add(null);
            home.getNames().put(Locale.GERMAN, "Mannschaft Alpha");

            List<MarketDescription> described = markets.getMarketDescriptions();
            described.sort(Comparator.comparingInt(MarketDescription::getId).reversed());
            MarketDescription market = described.get(described.size() - 1);
            market.getOutcomes().clear();
            market.getGroups().add("mine");
            markets.getMarketVoidReasons()
                    .sort(Comparator.comparingInt(MarketVoidReason::getId).reversed());
            rest.awaitQuiet();
        }
    }
}
