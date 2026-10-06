package com.oddin.oddsfeedsdk.internal.dispatch;

import static com.oddin.oddsfeedsdk.internal.message.MessageWorld.MATCH;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.PeriodScore;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Scoreboard;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.amqp.SessionQueue;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.message.MessageWorld;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlementCertainty;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChangeType;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsDisplayType;
import com.oddin.oddsfeedsdk.mq.entities.Outcome;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeOdds;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeResult;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeSettlement;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.VoidFactor;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Every vendored feed fixture of a message the client gets, taken through a session the way the feed
 * delivers it, and every field of what the client then reads: the message, its markets and outcomes,
 * and for an odds change that carries a match status, the match's status in the callback - each
 * period score and the scoreboard getter by getter, so a field that lands in the wrong place, or in
 * none, fails here. The decoded XML classes have {@code FeedGoldenTest}; this is what the client sees
 * of them. The fixtures carry a fixed timestamp; each is stamped now, so the live state takes it as
 * current.
 */
class FeedMessageGoldenTest {

    private static final Path FIXTURES = vendoredFeedFixtures();
    /** Neither reaches a client callback: the recovery actor takes them. */
    private static final List<String> NOT_DELIVERED = List.of("alive", "snapshot_complete");

    private final MessageWorld world = MessageWorld.start();
    private final EventsDispatcher events = new EventsDispatcher(new Quiet(), null, world.producers::getProducer);
    private final List<EventMessage<?>> delivered = new ArrayList<>();
    private final List<@Nullable MatchStatus> statuses = new ArrayList<>();
    private long now;

    @AfterEach
    void close() {
        events.close();
        world.close();
    }

    /** A fixture added upstream must get its golden test here, not pass unseen. */
    @Test
    void everyFixtureOfADeliveredMessageHasItsGoldenTest() throws IOException {
        var fixtures = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            files.filter(file -> file.toString().endsWith(".xml"))
                    .map(file -> FIXTURES.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> NOT_DELIVERED.stream().noneMatch(name::startsWith))
                    .forEach(fixtures::add);
        }
        var tested = new TreeSet<String>();
        for (Method method : getClass().getDeclaredMethods()) {
            Golden golden = method.getAnnotation(Golden.class);
            if (golden != null) {
                tested.add(golden.value());
            }
        }
        assertThat(tested).containsExactlyElementsOf(fixtures);
    }

    // ---- odds changes

    @Test
    @Golden("odds_change/odds_change_markets_only.xml")
    void oddsChangeMarketsOnly() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_markets_only.xml");
        assertMessage(change, 2049987833L);
        List<MarketWithOdds> markets = change.getMarkets();
        assertThat(markets)
                .extracting(
                        Market::getId, Market::getSpecifiers, MarketWithOdds::getStatus, MarketWithOdds::isFavourite)
                .containsExactly(
                        tuple(1, Map.of(), MarketStatus.ACTIVE, true),
                        tuple(89, Map.of("threshold", "113.5"), MarketStatus.SUSPENDED, false),
                        tuple(1, Map.of("variant", "way:two", "way", "two"), MarketStatus.ACTIVE, false));
        assertThat(markets.get(0).getOutcomeOdds())
                .extracting(Outcome::getId, this::decimal, OutcomeOdds::getProbability, OutcomeOdds::isActive)
                .containsExactly(tuple("1", 1.5, 0.65, true), tuple("2", 2.5, 0.35, true));
        assertThat(markets.get(1).getOutcomeOdds())
                .extracting(Outcome::getId, this::decimal, OutcomeOdds::getProbability, OutcomeOdds::isActive)
                .containsExactly(tuple("4", null, null, false), tuple("5", null, null, false));
        assertThat(markets.get(2).getOutcomeOdds())
                .extracting(Outcome::getId, this::decimal, OutcomeOdds::getProbability, OutcomeOdds::isActive)
                .containsExactly(tuple("od:player:1234", 1.85, null, true));
        assertThat(markets.get(0).getName()).isEqualTo("Winner");
        assertThat(markets.get(0).getOutcomeOdds())
                .extracting(Outcome::getName)
                .containsExactly("Team Alpha", "Team Beta");
        assertOutcomesWithoutWhatTheFeedNeverSends(markets);
        assertThat(statuses).as("no sport_event_status, nothing of it read").containsOnlyNulls();
    }

    @Test
    @Golden("odds_change/odds_change_soccer_scoreboard.xml")
    void oddsChangeSoccerScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_soccer_scoreboard.xml");
        assertMessage(change, 101L);
        assertThat(change.getMarkets())
                .extracting(
                        Market::getId, Market::getSpecifiers, MarketWithOdds::getStatus, MarketWithOdds::isFavourite)
                .containsExactly(tuple(1, Map.of("variant", "way:two", "way", "two"), MarketStatus.ACTIVE, false));
        assertThat(change.getMarkets().getFirst().getOutcomeOdds())
                .extracting(Outcome::getId, this::decimal, OutcomeOdds::getProbability, OutcomeOdds::isActive)
                .containsExactly(tuple("1", 1.85, null, true), tuple("2", 1.85, null, true));
        assertOutcomesWithoutWhatTheFeedNeverSends(change.getMarkets());
        MatchStatus status = status(EventStatus.Live, 0, 1.0, 0.0, true);
        assertPeriods(
                status,
                Map.of(
                        "getPeriodType",
                        "half",
                        "getPeriodNumber",
                        1,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        1.0,
                        "getAwayScore",
                        0.0,
                        "getHomeGoals",
                        1,
                        "getAwayGoals",
                        0),
                period("half", 2, 0, 0.0, 0.0));
        assertScoreboard(
                status,
                Map.of("getHomeGoals", 1, "getAwayGoals", 0, "getTime", 65, "getGameTime", 120, "getElapsedTime", 0));
    }

    @Test
    @Golden("odds_change/odds_change_closed_with_winner.xml")
    void oddsChangeClosedWithWinner() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_closed_with_winner.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        // the feed's winner (KD-21)
        MatchStatus status = status(EventStatus.Finished, 1, 3.0, 2.0, false, URN.parse("od:competitor:47214"));
        assertPeriods(status, period("round", 1, 0, 1.0, 0.0), period("round", 2, 0, 0.0, 1.0));
        assertThat(status.getScoreboard()).isNull();
    }

    @Test
    @Golden("odds_change/odds_change_cricket_scoreboard.xml")
    void oddsChangeCricketScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_cricket_scoreboard.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 0, 0.0, 0.0, true);
        assertPeriods(
                status,
                Map.ofEntries(
                        entry("getPeriodType", "inning"),
                        entry("getPeriodNumber", 1),
                        entry("getMatchStatusCode", 0),
                        entry("getHomeScore", 156.0),
                        entry("getAwayScore", 120.0),
                        entry("getHomeRuns", 156),
                        entry("getAwayRuns", 120),
                        entry("getHomeWicketsFallen", 4),
                        entry("getAwayWicketsFallen", 6),
                        entry("getHomeOversPlayed", 15),
                        entry("getAwayOversPlayed", 18),
                        entry("getHomeBallsPlayed", 3),
                        entry("getAwayBallsPlayed", 2),
                        entry("getHomeWonCoinToss", true)));
        assertScoreboard(
                status,
                Map.ofEntries(
                        entry("getHomeRuns", 156),
                        entry("getAwayRuns", 120),
                        entry("getHomeWicketsFallen", 4),
                        entry("getAwayWicketsFallen", 6),
                        entry("getHomeOversPlayed", 15),
                        entry("getHomeBallsPlayed", 3),
                        entry("getAwayOversPlayed", 18),
                        entry("getAwayBallsPlayed", 2),
                        entry("getHomeWonCoinToss", true),
                        entry("getHomeBatting", true),
                        entry("getAwayBatting", false),
                        entry("getInning", 1)));
    }

    @Test
    @Golden("odds_change/odds_change_moba_scoreboard.xml")
    void oddsChangeMobaScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_moba_scoreboard.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 0, 0.0, 0.0, true);
        assertPeriods(status);
        assertScoreboard(
                status,
                Map.of(
                        "getHomeKills",
                        20,
                        "getAwayKills",
                        18,
                        "getHomeDestroyedTurrets",
                        3,
                        "getAwayDestroyedTurrets",
                        1,
                        "getHomeDestroyedTowers",
                        4,
                        "getAwayDestroyedTowers",
                        2,
                        "getHomeGold",
                        52000,
                        "getAwayGold",
                        48000));
    }

    @Test
    @Golden("odds_change/odds_change_points_scoreboard.xml")
    void oddsChangePointsScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_points_scoreboard.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 0, 92.0, 88.0, true);
        assertPeriods(
                status,
                Map.of(
                        "getPeriodType",
                        "quarter",
                        "getPeriodNumber",
                        4,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        21.0,
                        "getAwayScore",
                        14.0,
                        "getHomePoints",
                        21,
                        "getAwayPoints",
                        14));
        assertScoreboard(
                status,
                Map.of("getHomePoints", 92, "getAwayPoints", 88, "getRemainingGameTime", 45, "getElapsedTime", 675));
    }

    @Test
    @Golden("odds_change/odds_change_rounds_scoreboard.xml")
    void oddsChangeRoundsScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_rounds_scoreboard.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 0, 1.0, 0.0, true);
        assertPeriods(
                status,
                Map.of(
                        "getPeriodType",
                        "map",
                        "getPeriodNumber",
                        1,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        1.0,
                        "getAwayScore",
                        0.0,
                        "getHomeWonRounds",
                        16,
                        "getAwayWonRounds",
                        9),
                period("map", 2, 0, 0.0, 0.0));
        assertScoreboard(
                status,
                Map.of(
                        "getCurrentCtTeam",
                        1,
                        "getCurrentDefenderTeam",
                        2,
                        "getHomeWonRounds",
                        10,
                        "getAwayWonRounds",
                        5,
                        "getCurrentRound",
                        16));
    }

    @Test
    @Golden("odds_change/odds_change_set_based_classic_sport.xml")
    void oddsChangeSetBasedClassicSport() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_set_based_classic_sport.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 1, 1.0, 1.0, false);
        assertPeriods(
                status,
                Map.of(
                        "getPeriodType",
                        "set",
                        "getPeriodNumber",
                        1,
                        "getMatchStatusCode",
                        1,
                        "getHomeScore",
                        1.0,
                        "getAwayScore",
                        0.0,
                        "getHomeGames",
                        6,
                        "getAwayGames",
                        4),
                Map.of(
                        "getPeriodType",
                        "set",
                        "getPeriodNumber",
                        2,
                        "getMatchStatusCode",
                        1,
                        "getHomeScore",
                        0.0,
                        "getAwayScore",
                        1.0,
                        "getHomeGames",
                        3,
                        "getAwayGames",
                        6),
                Map.of(
                        "getPeriodType",
                        "set",
                        "getPeriodNumber",
                        3,
                        "getMatchStatusCode",
                        1,
                        "getHomeScore",
                        0.0,
                        "getAwayScore",
                        0.0,
                        "getHomeGames",
                        2,
                        "getAwayGames",
                        1));
        assertThat(status.getScoreboard()).isNull();
    }

    @Test
    @Golden("odds_change/odds_change_table_tennis_scoreboard.xml")
    void oddsChangeTableTennisScoreboard() {
        var change = (OddsChange<?>) deliver("odds_change/odds_change_table_tennis_scoreboard.xml");
        assertMessage(change, null);
        assertThat(change.getMarkets()).as("an empty odds element").isEmpty();
        MatchStatus status = status(EventStatus.Live, 0, 2.0, 1.0, true);
        assertPeriods(
                status,
                Map.of(
                        "getPeriodType",
                        "game",
                        "getPeriodNumber",
                        1,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        11.0,
                        "getAwayScore",
                        7.0,
                        "getHomePoints",
                        11,
                        "getAwayPoints",
                        7),
                Map.of(
                        "getPeriodType",
                        "game",
                        "getPeriodNumber",
                        2,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        9.0,
                        "getAwayScore",
                        11.0,
                        "getHomePoints",
                        9,
                        "getAwayPoints",
                        11),
                Map.of(
                        "getPeriodType",
                        "game",
                        "getPeriodNumber",
                        3,
                        "getMatchStatusCode",
                        0,
                        "getHomeScore",
                        11.0,
                        "getAwayScore",
                        4.0,
                        "getHomePoints",
                        11,
                        "getAwayPoints",
                        4));
        assertScoreboard(status, Map.of("getHomeGames", 2, "getAwayGames", 1));
    }

    // ---- the other messages

    @Test
    @Golden("bet_stop/bet_stop_all_groups.xml")
    void betStopAllGroups() {
        var stop = (BetStop<?>) deliver("bet_stop/bet_stop_all_groups.xml");
        assertMessage(stop, null);
        assertThat(stop.getGroups()).containsExactly("all");
        assertThat(stop.getMarketStatus()).isEqualTo(MarketStatus.SUSPENDED);
    }

    @Test
    @Golden("bet_stop/bet_stop_minimal.xml")
    void betStopMinimal() {
        var stop = (BetStop<?>) deliver("bet_stop/bet_stop_minimal.xml");
        assertMessage(stop, null);
        assertThat(stop.getGroups()).isNull();
        assertThatThrownBy(stop::getMarketStatus).as("none sent, as in 0.0.x").isInstanceOf(NullPointerException.class);
    }

    @Test
    @Golden("bet_settlement/bet_settlement.xml")
    @SuppressWarnings("deprecation") // the getters the feed never sends are part of the golden
    void betSettlement() {
        var settlement = (BetSettlement<?>) deliver("bet_settlement/bet_settlement.xml");
        assertMessage(settlement, null);
        assertThat(settlement.getCertainty()).isEqualTo(BetSettlementCertainty.UNKNOWN);
        assertThat(settlement.getMarkets())
                .extracting(Market::getId, Market::getSpecifiers, m -> m.getVoidReason(), m -> m.getVoidReasonValue())
                .containsExactly(tuple(1, Map.of(), null, null), tuple(42, Map.of("setnr", "1"), null, null));
        assertThat(settlement.getMarkets().get(0).getOutcomeSettlements())
                .extracting(
                        Outcome::getId,
                        OutcomeSettlement::getOutcomeResult,
                        OutcomeSettlement::getVoidFactor,
                        OutcomeSettlement::getDeadHeatFactor,
                        Outcome::getRefId)
                .containsExactly(
                        tuple("1", OutcomeResult.WON, null, null, null),
                        tuple("2", OutcomeResult.LOST, null, null, null));
        assertThat(settlement.getMarkets().get(1).getOutcomeSettlements())
                .extracting(Outcome::getId, OutcomeSettlement::getOutcomeResult, OutcomeSettlement::getVoidFactor)
                .containsExactly(tuple("1", OutcomeResult.WON, VoidFactor.REFUND_HALF));
        assertThat(settlement.getMarkets().getFirst().getName()).isEqualTo("Winner");
    }

    @Test
    @Golden("rollback_bet_settlement/rollback_bet_settlement.xml")
    void rollbackBetSettlement() {
        var rollback = (RollbackBetSettlement<?>) deliver("rollback_bet_settlement/rollback_bet_settlement.xml");
        assertMessage(rollback, null);
        assertThat(rollback.getMarkets())
                .extracting(Market::getId, Market::getSpecifiers)
                .containsExactly(tuple(1, Map.of()), tuple(2, Map.of("mapnr", "2")));
    }

    @Test
    @Golden("bet_cancel/bet_cancel.xml")
    @SuppressWarnings("deprecation") // the getters the feed never sends are part of the golden, and KD-5's
    void betCancel() {
        var cancel = (BetCancel<?>) deliver("bet_cancel/bet_cancel.xml");
        assertMessage(cancel, null);
        assertThat(cancel.getStartTime()).isEqualTo(new Date(1777800000000L));
        assertThat(cancel.getEndTime()).isEqualTo(new Date(1777832981000L));
        assertThat(cancel.getSupercededBy()).isNull();
        assertThat(cancel.getMarkets())
                .extracting(
                        Market::getId,
                        Market::getSpecifiers,
                        MarketCancel::getVoidReason,
                        MarketCancel::getVoidReasonId,
                        MarketCancel::getVoidReasonParams,
                        MarketCancel::getVoidReasonValue,
                        Market::getRefId)
                .containsExactly(
                        tuple(1, Map.of(), null, null, null, null, null),
                        tuple(42, Map.of("setnr", "1"), null, null, null, null, null),
                        tuple(17, Map.of("mapnr", "2"), "1", 4, "minutes=5", null, null));
    }

    @Test
    @Golden("rollback_bet_cancel/rollback_bet_cancel.xml")
    void rollbackBetCancel() {
        var rollback = (RollbackBetCancel<?>) deliver("rollback_bet_cancel/rollback_bet_cancel.xml");
        assertMessage(rollback, null);
        assertThat(rollback.getStartTime()).isEqualTo(new Date(1777800000000L));
        assertThat(rollback.getEndTime()).isEqualTo(new Date(1777832981000L));
        assertThat(rollback.getMarkets())
                .extracting(Market::getId, Market::getSpecifiers)
                .containsExactly(tuple(1, Map.of()));
    }

    @Test
    @Golden("fixture_change/fixture_change.xml")
    @SuppressWarnings("deprecation") // the getters the feed never sends are part of the golden
    void fixtureChange() {
        var change = (FixtureChange<?>) deliver("fixture_change/fixture_change.xml");
        assertMessage(change, null);
        assertThat(change.getChangeType()).isEqualTo(FixtureChangeType.NEW);
        assertThat(change.getNextLiveTime()).isNull();
        assertThat(change.getStartTime()).as("KD-4").isNull();
    }

    // ---- helpers

    /** Takes the fixture through a session, from producer 2 and stamped now; what the callback got. */
    private EventMessage<?> deliver(String fixture) {
        now = System.currentTimeMillis();
        String xml = FeedMessages.stampedAt(MessageWorld.fromLiveProducer(Fixtures.read("feed/" + fixture)), now);
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        var pipeline = new Pipeline(
                world.decoder,
                world.messages,
                world.matches,
                world.profiles,
                world.producers,
                new FixtureChanges(),
                new ClockOffsets(world.producers),
                events,
                InstantSource.system());
        var dispatcher = new SessionDispatcher(
                1,
                new OddsFeedSession() {},
                MessageInterest.ALL,
                new Listener(),
                null,
                new Transport(),
                null,
                false,
                pipeline);
        dispatcher.handle(new RawDelivery(body, body.length, FakeFeed.routingKey(xml), 1, 0, Instant.now(), null));
        assertThat(delivered).as("delivered").hasSize(1);
        assertThat(dispatcher.sdkFailures() + dispatcher.callbackFailures())
                .as("failures")
                .isZero();
        return delivered.getFirst();
    }

    private void assertMessage(EventMessage<?> message, @Nullable Long requestId) {
        assertThat(message.getEvent().getId()).isEqualTo(MATCH);
        assertThat(message.getEvent()).isInstanceOf(Match.class);
        assertThat(requireNonNull(message.getProducer()).getId()).isEqualTo(2);
        assertThat(message.getRequestId()).isEqualTo(requestId);
        assertThat(message.getTimestamp().getCreated()).isEqualTo(now);
        assertThat(new String(message.getRawMessage(), StandardCharsets.UTF_8)).contains("timestamp=\"" + now + "\"");
    }

    @SuppressWarnings("deprecation") // the getters the feed never sends are part of the golden
    private static void assertOutcomesWithoutWhatTheFeedNeverSends(List<MarketWithOdds> markets) {
        for (MarketWithOdds market : markets) {
            assertThat(market.getRefId()).isNull();
            for (OutcomeOdds outcome : market.getOutcomeOdds()) {
                assertThat(outcome.getRefId()).isNull();
                assertThat(outcome.getAdditionalProbabilities()).isNull();
                assertThat(outcome.isPlayerOutcome()).isFalse();
            }
        }
    }

    private @Nullable Double decimal(OutcomeOdds outcome) {
        return outcome.getOdds(OddsDisplayType.DECIMAL);
    }

    /** The match's status as the callback read it, of a fixture without a winner. */
    private MatchStatus status(EventStatus expected, int matchStatusId, double home, double away, boolean scoreboard) {
        return status(expected, matchStatusId, home, away, scoreboard, null);
    }

    /**
     * The match's status as the callback read it, its own getters asserted as the period scores' are: a
     * getter not given here must return null. The period scores and the scoreboard are asserted apart,
     * and nothing in this world describes a match status, so {@code getMatchStatus()} is null.
     */
    private MatchStatus status(
            EventStatus expected,
            int matchStatusId,
            double home,
            double away,
            boolean scoreboard,
            @Nullable URN winner) {
        MatchStatus status = requireNonNull(statuses.getFirst(), "the match's status in the callback");
        var expectedValues = new TreeMap<String, Object>(Map.of(
                "getStatus", expected,
                "getMatchStatusId", matchStatusId,
                "getHomeScore", home,
                "getAwayScore", away,
                "isScoreboardAvailable", scoreboard,
                "getProperties", Map.of()));
        if (winner != null) {
            expectedValues.put("getWinnerId", winner);
        }
        Map<String, Object> read = values(status, MatchStatus.class);
        read.remove("getPeriodScores");
        read.remove("getScoreboard");
        assertThat(read).as("the match status").isEqualTo(expectedValues);
        return status;
    }

    @SafeVarargs
    private static void assertPeriods(MatchStatus status, Map<String, Object>... expected) {
        List<PeriodScore> periods = requireNonNull(status.getPeriodScores());
        assertThat(periods).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(values(periods.get(i), PeriodScore.class))
                    .as("period score %s", i)
                    .isEqualTo(new TreeMap<>(expected[i]));
        }
    }

    private static void assertScoreboard(MatchStatus status, Map<String, Object> expected) {
        Scoreboard scoreboard = requireNonNull(status.getScoreboard(), "a scoreboard");
        assertThat(values(scoreboard, Scoreboard.class)).isEqualTo(new TreeMap<>(expected));
    }

    private static Map<String, Object> period(String type, int number, int code, double home, double away) {
        return Map.of(
                "getPeriodType", type,
                "getPeriodNumber", number,
                "getMatchStatusCode", code,
                "getHomeScore", home,
                "getAwayScore", away);
    }

    /** Every getter of {@code type} that returns something, by name: what is not here is null. */
    private static Map<String, Object> values(Object value, Class<?> type) {
        var values = new TreeMap<String, Object>();
        for (Method getter : type.getMethods()) {
            if (getter.getParameterCount() != 0
                    || Modifier.isStatic(getter.getModifiers())
                    || getter.getDeclaringClass() == Object.class
                    || !(getter.getName().startsWith("get") || getter.getName().startsWith("is"))) {
                continue;
            }
            try {
                Object read = getter.invoke(value);
                if (read != null) {
                    values.put(getter.getName(), read);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new LinkageError(getter + " could not be read", e);
            }
        }
        return values;
    }

    private static Path vendoredFeedFixtures() {
        try {
            Path start = Path.of(FeedMessageGoldenTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path fixtures = dir.resolve("vendor/oddsfeedschema/test/fixtures/feed");
                if (Files.isDirectory(fixtures)) {
                    return fixtures;
                }
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("the vendored feed fixtures are not above " + FeedMessageGoldenTest.class);
    }

    /** The fixture a golden test is for, under the vendored feed fixtures. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    private @interface Golden {
        String value();
    }

    /** Keeps what the session delivers, and the match's status as the callback read it. */
    private final class Listener implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            delivered.add(message);
            Match match = (Match) message.getEvent();
            // read only when the message carries a status: else the read loads the summary
            statuses.add(
                    new String(message.getRawMessage(), StandardCharsets.UTF_8).contains("<sport_event_status")
                            ? match.getStatus()
                            : null);
        }

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
            delivered.add(message);
        }

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
            delivered.add(message);
        }

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
            delivered.add(message);
        }

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
            delivered.add(message);
        }

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
            delivered.add(message);
        }

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
            delivered.add(message);
        }
    }

    private static final class Transport implements SessionTransport {
        private final SessionQueue queue = new SessionQueue(1);

        @Override
        public void ack(RawDelivery delivery) {}

        @Override
        public void reset() {}

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public long epoch() {
            return 0;
        }

        @Override
        public SessionQueue queue() {
            return queue;
        }
    }

    private static final class Quiet implements GlobalEventsListener {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
