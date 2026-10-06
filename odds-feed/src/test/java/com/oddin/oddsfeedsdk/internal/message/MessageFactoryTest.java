package com.oddin.oddsfeedsdk.internal.message;

import static com.oddin.oddsfeedsdk.internal.message.MessageWorld.MATCH;
import static java.util.Objects.requireNonNull;
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
import com.oddin.oddsfeedsdk.schema.feed.v1.OFChangeType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
    void aSpecifiersValueIsNotFilledInAgain() throws Exception {
        world.api.respond(MARKETS, 200, markets("""
                <market id="2" name="{team} to win map {mapnr}, {team} again, {unknown} as it is" groups="all"/>
                """));
        // each link names the next twice: filled in again, link after link, the name would double at each
        var chain = new StringBuilder("team={s0}{s0}");
        for (int i = 0; i < 40; i++) {
            chain.append("|s%d={s%d}{s%d}".formatted(i, i + 1, i + 1));
        }
        var change = (OddsChange<?>)
                world.build(oddsChange("<market id=\"2\" specifiers=\"" + chain + "|mapnr=2\" status=\"1\"/>"));
        assertThat(change.getMarkets().getFirst().getName())
                .isEqualTo("{s0}{s0} to win map 2, {s0}{s0} again, {unknown} as it is");
    }

    /** What a replacement reads as a group or an escape is text in a name, as 0.0.x's plain replace kept it. */
    @Test
    void aFilledInValueIsTakenAsItIsDollarsAndBackslashesIncluded() throws Exception {
        String odd = "$1 ${x} \\";
        world.api.respond(MARKETS, 200, markets("""
                <market id="2" name="{team} to win map {mapnr}" groups="all"/>
                <market id="3" name="{player} to score" groups="all|player_props"/>
                """));
        world.api.respond(
                "/v1/sports/en/competitors/od:competitor:47215/profile",
                200,
                Fixtures.read("rest/competitor/competitor_profile_no_players.xml")
                        .replace("od:competitor:47214", "od:competitor:47215")
                        .replace("Team Alpha", "Team " + odd));
        // a player no competitor profile lists, so only its own profile names it
        world.api.respond(
                "/v1/sports/en/players/od:player:9002/profile",
                200,
                Fixtures.read("rest/player/player_profile.xml")
                        .replace("od:player:9001", "od:player:9002")
                        .replace("Player One", "Player " + odd));
        var change = (OddsChange<?>) world.build(oddsChange("""
                <market id="2" specifiers="team=away|mapnr=%s" status="1"/>
                <market id="2" specifiers="team=%s|mapnr=1" status="1"/>
                <market id="3" specifiers="player=od:player:9002" status="1"/>
                """.formatted(odd, odd)));
        assertThat(change.getMarkets())
                .extracting(Market::getName)
                .containsExactly(
                        "Team $1 ${x} \\ to win map $1 ${x} \\",
                        "$1 ${x} \\ to win map 1",
                        "Player $1 ${x} \\ to score");
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
    void aMessagesNamesReadTheCatalogAsItWasWhenFirstAskedFor() throws Exception {
        var change = (OddsChange<?>) world.fixture(ODDS_CHANGE);
        MarketWithOdds winner = change.getMarkets().getFirst();
        assertThat(winner.getName()).isEqualTo("Winner");
        world.api.respond(
                MARKETS,
                200,
                markets("<market id=\"1\" name=\"Match Winner\" groups=\"all\"><outcomes><outcome id=\"1\" "
                        + "name=\"home\"/><outcome id=\"2\" name=\"away\"/></outcomes></market>"));
        world.catalog.clear();

        assertThat(winner.getName()).as("the message's").isEqualTo("Winner");
        var later = (OddsChange<?>) world.fixture(ODDS_CHANGE);
        assertThat(later.getMarkets().getFirst().getName())
                .as("a later message's")
                .isEqualTo("Match Winner");
    }

    @Test
    void anOutcomeNamedHomeOfAnEventWithoutCompetitorsIsNullUnderThrowAsIn0x() throws Exception {
        world.api.respond(MARKETS, 200, markets("""
                <market id="1" name="Winner" groups="all"><outcomes><outcome id="1" name="home"/>\
                <outcome id="2" name="away"/><outcome id="3" name="draw"/></outcomes></market>
                """));
        String xml = oddsChange(
                        "<market id=\"1\" status=\"1\"><outcome id=\"1\" odds=\"2\"/><outcome id=\"3\" odds=\"2\"/></market>")
                .replace("od:match:198314", "od:tournament:1042");
        var message = world.decode(xml);
        var change = (OddsChange<?>) world.messages.build(
                message,
                world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:tournament.1042.-")),
                requireNonNull(world.producers.getProducer(2)),
                xml.getBytes(StandardCharsets.UTF_8),
                new MessageTimestamp(1, 1, 1, 1));
        assertThat(requireNonNull(change).getMarkets().getFirst().getOutcomeOdds())
                .extracting(Outcome::getName)
                .as("a tournament has no home competitor; the description has the outcome")
                .containsExactly(null, "draw");
    }

    @Test
    void aMessagesMarketsAreOneListForEveryReader() throws Exception {
        MarketWithOdds market =
                ((OddsChange<?>) world.fixture(ODDS_CHANGE)).getMarkets().getFirst();
        var calls = new AtomicInteger();
        var nested = new AtomicReference<List<MarketWithOdds>>();
        var holder = new AtomicReference<OddsChangeMessage>();
        // the first build is overtaken by a second reader, which builds and stores first
        var change = new OddsChangeMessage(
                world.messages.event(MessageWorld.route("hi.-.live.odds_change.5.od:match.198314.-")),
                (OFOddsChange) world.decode(Fixtures.read(ODDS_CHANGE)),
                new byte[0],
                requireNonNull(world.producers.getProducer(2)),
                new MessageTimestamp(1, 1, 1, 1),
                () -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        nested.set(holder.get().getMarkets());
                    }
                    // the first build two markets, the second - the other reader's - one: which was kept shows
                    return Collections.nCopies(3 - call, market);
                });
        holder.set(change);
        List<MarketWithOdds> first = change.getMarkets();
        assertThat(first).as("the markets the other reader stored").hasSize(1).isEqualTo(nested.get());
        assertThat(change.getMarkets()).containsExactlyElementsOf(first);
        assertThat(calls).hasValue(2);
    }

    /** As 0.0.x's lists of two or more, and every list since the compat rule: the client's to change. */
    @Test
    void everyCollectionAMessageGivesIsANewOneTheClientMayChange() throws Exception {
        var change = (OddsChange<?>) world.fixture(ODDS_CHANGE);
        assertChangeable(change::getMarkets);
        MarketWithOdds market = change.getMarkets().getFirst();
        assertChangeable(market::getOutcomeOdds);
        MarketWithOdds specified = change.getMarkets().stream()
                .filter(each -> !each.getSpecifiers().isEmpty())
                .findFirst()
                .orElseThrow();
        Map<String, String> specifiers = specified.getSpecifiers();
        assertThat(specified.getSpecifiers()).as("a new one").isNotSameAs(specifiers);
        Map<String, String> before = Map.copyOf(specifiers);
        specifiers.clear();
        assertThat(specified.getSpecifiers()).isEqualTo(before);
        // in the feed's order, which a hash map's would not keep: theirs is u to z
        var ordered = (OddsChange<?>)
                world.build(oddsChange("<market id=\"1\" specifiers=\"z=1|y=2|x=3|w=4|v=5|u=6\" status=\"1\"/>"));
        assertThat(ordered.getMarkets().getFirst().getSpecifiers().keySet())
                .containsExactly("z", "y", "x", "w", "v", "u");

        var settlement = (BetSettlement<?>) world.fixture("feed/bet_settlement/bet_settlement.xml");
        assertChangeable(settlement::getMarkets);
        assertChangeable(() -> settlement.getMarkets().getFirst().getOutcomeSettlements());
        var cancel = (BetCancel<?>) world.fixture("feed/bet_cancel/bet_cancel.xml");
        assertChangeable(cancel::getMarkets);
        var stop = (BetStop<?>) world.build(Fixtures.read(BET_STOP).replace("groups=\"all\"", "groups=\"winner\""));
        assertChangeable(() -> requireNonNull(stop.getGroups()));
        var rollbackSettlement = (RollbackBetSettlement<?>) world.build(MessageWorld.fromLiveProducer(
                Fixtures.read("feed/rollback_bet_settlement/rollback_bet_settlement.xml")));
        assertChangeable(rollbackSettlement::getMarkets);
        var rollbackCancel = (RollbackBetCancel<?>) world.build(
                MessageWorld.fromLiveProducer(Fixtures.read("feed/rollback_bet_cancel/rollback_bet_cancel.xml")));
        assertChangeable(rollbackCancel::getMarkets);
    }

    /** A new collection on each call, changeable, and a change to it changes no later call's. */
    private static <T> void assertChangeable(Supplier<? extends Collection<T>> getter) {
        Collection<T> given = getter.get();
        assertThat(given).as("something to change").isNotEmpty();
        List<T> before = List.copyOf(given);
        assertThat(getter.get()).as("a new one").isNotSameAs(given);
        given.clear();
        assertThat(getter.get()).as("unchanged by the client's change").containsExactlyElementsOf(before);
    }

    @Test
    void theEnumMappingsCompanionsReachTheSameMappings() {
        assertThat(MarketStatus.Companion.fromFeedValue(
                        com.oddin.oddsfeedsdk.schema.feed.v1.OFMarketStatus.HANDED_OVER))
                .isEqualTo(MarketStatus.HANDED_OVER);
        assertThat(FixtureChangeType.Companion.fromFeedType(OFChangeType.DATETIME))
                .isEqualTo(FixtureChangeType.TIME_UPDATE);
    }

    @Test
    @SuppressWarnings("deprecation") // FORMAT is no longer sent, and still maps as in 0.0.x
    void everyChangeTypeOfTheFeedMapsAsIn0x() {
        var expected = new EnumMap<OFChangeType, FixtureChangeType>(Map.of(
                OFChangeType.NEW, FixtureChangeType.NEW,
                OFChangeType.DATETIME, FixtureChangeType.TIME_UPDATE,
                OFChangeType.CANCELLED, FixtureChangeType.CANCELLED,
                OFChangeType.FORMAT, FixtureChangeType.OTHER_CHANGE,
                OFChangeType.COVERAGE, FixtureChangeType.COVERAGE,
                OFChangeType.STREAM_URL, FixtureChangeType.STREAM_URL,
                OFChangeType.UNKNOWN, FixtureChangeType.OTHER_CHANGE));
        assertThat(expected.keySet()).as("every type the feed has").containsExactly(OFChangeType.values());
        expected.forEach((type, mapped) ->
                assertThat(FixtureChangeType.fromFeedType(type)).as("%s", type).isEqualTo(mapped));
        assertThat(FixtureChangeType.fromFeedType(null)).as("none sent").isEqualTo(FixtureChangeType.OTHER_CHANGE);
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
