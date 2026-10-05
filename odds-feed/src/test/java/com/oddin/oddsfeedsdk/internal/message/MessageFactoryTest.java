package com.oddin.oddsfeedsdk.internal.message;

import static com.oddin.oddsfeedsdk.internal.message.MessageWorld.MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChangeType;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsDisplayType;
import com.oddin.oddsfeedsdk.mq.entities.Outcome;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeOdds;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The messages the factory builds from the feed's XML: the rules 0.0.x kept and the ones 1.0 fixes,
 * the unknown wire values, and the names of markets and outcomes.
 */
class MessageFactoryTest {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String BET_STOP = "feed/bet_stop/bet_stop_all_groups.xml";
    private static final String MARKETS = "/v1/descriptions/en/markets";

    private MessageWorld world = MessageWorld.start();

    @AfterEach
    void close() {
        world.close();
    }

    // ---- the event

    @Test
    void theEventIsTheMatchOrTournamentTheRoutingKeyNames() {
        assertThat(world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:match.198314.-")))
                .isInstanceOfSatisfying(
                        Match.class, match -> assertThat(match.getId()).isEqualTo(MATCH));
        assertThat(world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:tournament.7.-")))
                .isInstanceOfSatisfying(
                        Tournament.class,
                        tournament -> assertThat(tournament.getId()).isEqualTo(URN.parse("od:tournament:7")));
    }

    @Test
    void anEventTheSdkCannotBuildFailsTheBuild() {
        assertThatThrownBy(() -> world.messages.event(MessageWorld.route("hi.-.live.odds_change.-.od:tournament.7.-")))
                .as("a tournament without its sport")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:season.7.-")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("season");
        assertThatThrownBy(() -> world.messages.event(MessageWorld.route("-.-.-.alive.-.-.-.-")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnparsableMessageIsForTheMatchItsRoutingKeyNames() {
        var timestamp = new MessageTimestamp(0, 1, 2, 3);
        byte[] raw = {'<'};
        UnparsableMessage<?> unparsable = world.messages.unparsable(
                MessageWorld.route("hi.-.live.odds_change.-.od:match.198314.-"), raw, timestamp);
        assertThat(unparsable.getEvent()).isInstanceOf(Match.class);
        assertThat(unparsable.getEvent().getId()).isEqualTo(MATCH);
        assertThat(unparsable.getRawMessage()).isSameAs(raw);
        assertThat(unparsable.getProducer()).as("no producer, as in 0.0.x").isNull();
        assertThat(unparsable.getTimestamp()).isSameAs(timestamp);
        assertThatThrownBy(() -> world.messages.unparsable(MessageWorld.route("-.-.-.alive.-.-.-.-"), raw, timestamp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- unknown wire values, as in 0.0.x

    @Test
    void anOddsChangeWithAMarketStatusTheSdkDoesNotKnowThrowsOnlyFromItsMarkets() throws Exception {
        var change = (OddsChange<?>) world.build(Fixtures.read(ODDS_CHANGE).replace("status=\"-1\"", "status=\"7\""));
        assertThat(change.getEvent().getId()).isEqualTo(MATCH);
        assertThatThrownBy(change::getMarkets).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(change::getMarkets).as("again").isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOddsChangeMarketWithoutAStatusThrowsFromTheMarketsAsIn0x() throws Exception {
        var change = (OddsChange<?>) world.build(Fixtures.read(ODDS_CHANGE).replace(" status=\"-1\"", ""));
        assertThatThrownBy(change::getMarkets).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBetStopsMarketStatusTheSdkDoesNotKnowOrNoneThrowsFromItsGetter() throws Exception {
        var unknown = (BetStop<?>)
                world.build(Fixtures.read(BET_STOP).replace("market_status=\"-1\"", "market_status=\"7\""));
        assertThat(unknown.getGroups()).containsExactly("all");
        assertThatThrownBy(unknown::getMarketStatus).isInstanceOf(NullPointerException.class);

        var none = (BetStop<?>) world.fixture("feed/bet_stop/bet_stop_minimal.xml");
        assertThat(none.getGroups()).as("none sent").isNull();
        assertThatThrownBy(none::getMarketStatus).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFixtureChangeOfATypeTheSdkDoesNotKnowIsAnotherChange() throws Exception {
        var change = (FixtureChange<?>) world.build(Fixtures.read("feed/fixture_change/fixture_change.xml")
                .replace("change_type=\"1\"", "change_type=\"99\""));
        assertThat(change.getChangeType()).isEqualTo(FixtureChangeType.OTHER_CHANGE);
    }

    @Test
    void aSettlementWithAResultTheSdkDoesNotKnowThrowsOnlyFromItsMarkets() throws Exception {
        var settlement = (BetSettlement<?>) world.build(
                Fixtures.read("feed/bet_settlement/bet_settlement.xml").replace("result=\"0\"", "result=\"5\""));
        assertThatThrownBy(settlement::getMarkets).isInstanceOf(NullPointerException.class);
    }

    // ---- KD-5 and KD-6

    @Test
    @SuppressWarnings("deprecation") // KD-5 is the deprecated getter
    void aCancelledMarketsVoidReasonIsTheOneItCarries() throws Exception {
        var cancel = (BetCancel<?>) world.fixture("feed/bet_cancel/bet_cancel.xml");
        assertThat(cancel.getMarkets())
                .extracting(
                        MarketCancel::getVoidReason, MarketCancel::getVoidReasonId, MarketCancel::getVoidReasonParams)
                .containsExactly(tuple(null, null, null), tuple(null, null, null), tuple("1", 4, "minutes=5"));
        assertThat(cancel.getMarkets())
                .extracting(MarketCancel::getVoidReasonValue)
                .containsOnlyNulls();
    }

    @Test
    void betStopGroupsAreSplitOnThePipe() throws Exception {
        var several = (BetStop<?>)
                world.build(Fixtures.read(BET_STOP).replace("groups=\"all\"", "groups=\"winner|handicap\""));
        assertThat(several.getGroups()).containsExactly("winner", "handicap");
        var trailing =
                (BetStop<?>) world.build(Fixtures.read(BET_STOP).replace("groups=\"all\"", "groups=\"winner|\""));
        assertThat(trailing.getGroups())
                .as("an empty last group, as Kotlin's split kept it")
                .containsExactly("winner", "");
    }

    // ---- what 0.0.x left out

    @Test
    void rollbacksHaveNoRequestIdAsIn0x() throws Exception {
        var settlement = (RollbackBetSettlement<?>) world.build(
                MessageWorld.fromLiveProducer(Fixtures.read("feed/rollback_bet_settlement/rollback_bet_settlement.xml")
                        .replace("timestamp=", "request_id=\"9\" timestamp=")));
        assertThat(settlement.getRequestId()).isNull();
        var cancel = (RollbackBetCancel<?>) world.build(
                MessageWorld.fromLiveProducer(Fixtures.read("feed/rollback_bet_cancel/rollback_bet_cancel.xml")
                        .replace("timestamp=", "request_id=\"9\" timestamp=")));
        assertThat(cancel.getRequestId()).isNull();
    }

    // ---- American odds (KD-24)

    @Test
    void americanOddsAreMoneylineOdds() {
        assertThat(OddsOutcome.american(2.5)).isEqualTo(150.0);
        assertThat(OddsOutcome.american(2.0)).isEqualTo(100.0);
        assertThat(OddsOutcome.american(3.0)).isEqualTo(200.0);
        assertThat(OddsOutcome.american(1.5)).isEqualTo(-200.0);
        assertThat(OddsOutcome.american(1.25)).isEqualTo(-400.0);
        assertThat(OddsOutcome.american(1.0)).as("no payout").isNull();
        assertThat(OddsOutcome.american(0.5)).isNull();
        assertThat(OddsOutcome.american(Double.NaN)).isNull();
        assertThat(OddsOutcome.american(Double.POSITIVE_INFINITY)).isNull();
        assertThat(OddsOutcome.american(null)).isNull();
    }

    // ---- specifiers

    @Test
    void specifiersAreThePairsSeparatedByAPipe() {
        assertThat(MessageFactory.specifiers("variant=way:two|way=two"))
                .containsExactly(Map.entry("variant", "way:two"), Map.entry("way", "two"));
        assertThat(MessageFactory.specifiers(null)).isEmpty();
        assertThat(MessageFactory.specifiers("")).isEmpty();
        assertThat(MessageFactory.specifiers("bad|a=b=c|x=|mapnr=2"))
                .as("what is not a pair is left out")
                .containsExactly(Map.entry("x", ""), Map.entry("mapnr", "2"));
    }

    // ---- names

    @Test
    void aMarketsNameIsItsDescriptionsWithItsSpecifiersFilledIn() throws Exception {
        world.api.respond(MARKETS, 200, markets("""
                <market id="1" name="Winner" groups="all"><outcomes><outcome id="1" name="home"/>\
                <outcome id="2" name="away"/><outcome id="3" name="draw"/></outcomes></market>
                <market id="2" name="{team} to win map {mapnr}" groups="all"/>
                <market id="3" name="{player} to score" groups="all|player_props"/>
                <market id="4" name="{player} to score" groups="all"/>
                """));
        world.api.respond(
                "/v1/sports/en/competitors/od:competitor:47215/profile",
                200,
                Fixtures.read("rest/competitor/competitor_profile_no_players.xml")
                        .replace("od:competitor:47214", "od:competitor:47215")
                        .replace("Team Alpha", "Team Beta"));
        var change = (OddsChange<?>) world.build(oddsChange("""
                <market id="1" status="1"><outcome id="1" odds="2"/><outcome id="2" odds="2"/><outcome id="3" odds="2"/></market>
                <market id="2" specifiers="team=away|mapnr=2" status="1"/>
                <market id="3" specifiers="player=od:player:9001" status="1"/>
                <market id="4" specifiers="player=od:player:9001" status="1"/>
                <market id="2" specifiers="team=nobody|mapnr=1" status="1"/>
                """));
        List<MarketWithOdds> markets = change.getMarkets();
        assertThat(markets)
                .extracting(Market::getName)
                .containsExactly(
                        "Winner",
                        "Team Beta to win map 2",
                        "Player One to score",
                        "od:player:9001 to score",
                        "nobody to win map 1");
        assertThat(markets.getFirst().getOutcomeOdds())
                .extracting(Outcome::getName)
                .as("home and away as the match's competitors")
                .containsExactly("Team Alpha", "Team Beta", "draw");
        assertThat(markets.getFirst().getName(Locale.ENGLISH)).isEqualTo("Winner");
    }

    @Test
    void anOutcomeTheDescriptionDoesNotListIsThePlayerOrCompetitorOfAMarketOfThem() throws Exception {
        world.api.respond("/v1/descriptions/en/markets/768/variants/od:dynamic_outcomes:770", 200, markets("""
                        <market id="768" name="Player to Score" variant="od:dynamic_outcomes:770" \
                        includes_outcomes_of_type="od:player" outcome_type="player"><outcomes>\
                        <outcome id="od:player:1" name="Listed Player"/></outcomes></market>
                        """));
        var change = (OddsChange<?>) world.build(oddsChange("""
                <market id="768" specifiers="variant=od:dynamic_outcomes:770" status="1">
                <outcome id="od:player:1" odds="2"/><outcome id="od:player:9001" odds="3"/></market>
                """));
        assertThat(change.getMarkets().getFirst().getOutcomeOdds())
                .extracting(Outcome::getName)
                .containsExactly("Listed Player", "Player One");
    }

    @Test
    void aMarketTheCatalogDoesNotHaveHasNoNameAsTheStrategySays() throws Exception {
        var change =
                (OddsChange<?>) world.build(oddsChange("<market id=\"999\" status=\"1\"><outcome id=\"1\"/></market>"));
        MarketWithOdds market = change.getMarkets().getFirst();
        assertThatThrownBy(market::getName)
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessage("Cannot find market name");
        assertThatThrownBy(() -> market.getOutcomeOdds().getFirst().getName())
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessage("Cannot find outcome name");

        world.close();
        world = MessageWorld.start(ExceptionHandlingStrategy.CATCH);
        var caught =
                (OddsChange<?>) world.build(oddsChange("<market id=\"999\" status=\"1\"><outcome id=\"1\"/></market>"));
        assertThat(caught.getMarkets().getFirst().getName()).isNull();
        assertThat(caught.getMarkets().getFirst().getOutcomeOdds().getFirst().getName())
                .isNull();
        world.api.startOutage(500);
        assertThat(caught.getMarkets().getFirst().getName(Locale.GERMAN))
                .as("a catalog that cannot be fetched")
                .isNull();
    }

    @Test
    void aCatalogThatCannotBeFetchedIsANameNotFoundUnderThrow() throws Exception {
        var change = (OddsChange<?>) world.build(oddsChange("<market id=\"1\" status=\"1\"/>"));
        world.api.startOutage(500);
        assertThatThrownBy(() -> change.getMarkets().getFirst().getName())
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessage("Cannot find market name")
                .hasCauseInstanceOf(com.oddin.oddsfeedsdk.exceptions.ApiException.class);
    }

    @Test
    void aMessagesNamesReadTheCatalogOncePerMarketAndLocale() throws Exception {
        var change = (OddsChange<?>) world.fixture(ODDS_CHANGE);
        MarketWithOdds winner = change.getMarkets().getFirst();
        assertThat(winner.getName()).isEqualTo("Winner");
        int requests = world.api.requests().size();
        for (OutcomeOdds outcome : winner.getOutcomeOdds()) {
            assertThat(outcome.getName()).isNotNull();
        }
        assertThat(winner.getName()).isEqualTo("Winner");
        assertThat(world.api.requests()).as("requests after the first name").hasSize(requests);
        assertThat(winner.getOutcomeOdds().getFirst().getOdds(OddsDisplayType.DECIMAL))
                .isEqualTo(1.5);
    }

    @Test
    void theEnumMappingsCompanionsReachTheSameMappings() {
        assertThat(MarketStatus.Companion.fromFeedValue(
                        com.oddin.oddsfeedsdk.schema.feed.v1.OFMarketStatus.HANDED_OVER))
                .isEqualTo(MarketStatus.HANDED_OVER);
        assertThat(FixtureChangeType.Companion.fromFeedType(com.oddin.oddsfeedsdk.schema.feed.v1.OFChangeType.DATETIME))
                .isEqualTo(FixtureChangeType.TIME_UPDATE);
    }

    private static String oddsChange(String markets) {
        return "<odds_change product=\"2\" event_id=\"od:match:198314\" timestamp=\"1777832981632\"><odds>" + markets
                + "</odds></odds_change>";
    }

    private static String markets(String markets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><market_descriptions response_code=\"OK\">" + markets
                + "</market_descriptions>";
    }
}
