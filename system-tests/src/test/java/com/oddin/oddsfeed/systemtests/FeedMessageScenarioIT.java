package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChangeType;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithSettlement;
import com.oddin.oddsfeedsdk.mq.entities.Outcome;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeResult;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeSettlement;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.VoidFactor;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One scenario per event message the feed sends besides odds change (see
 * {@link OddsChangeScenarioIT}): the fixture goes through the fake feed and reaches its listener
 * callback with what the fixture carries - the match, the producer, the time the feed stamped it
 * with, and the markets and outcomes.
 *
 * <p>Some fixtures come from producer 3, which the producers fixture does not list, and the SDK
 * only delivers messages from producers it knows and has enabled. Those are sent as producer 2,
 * the live producer; see {@link #fromLiveProducer}.
 */
class FeedMessageScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    /** The window both bet cancel fixtures cancel, and the rollback restores. */
    private static final Date WINDOW_START = new Date(1777800000000L);

    private static final Date WINDOW_END = new Date(1777832981000L);

    @Test
    void aBetStopReachesOnBetStop() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent = Sent.publishing(feed, Fixtures.read("feed/bet_stop/bet_stop_all_groups.xml"));

            BetStop<?> betStop = received.next(BetStop.class);
            assertThat(betStop.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(betStop.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(betStop.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(betStop.getGroups()).as("groups").containsExactly("all");
            assertThat(betStop.getMarketStatus()).as("market status").isEqualTo(MarketStatus.SUSPENDED);
        }
    }

    @Test
    void aBetSettlementReachesOnBetSettlementWithEveryOutcomeResult() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent = Sent.publishing(feed, Fixtures.read("feed/bet_settlement/bet_settlement.xml"));

            BetSettlement<?> settlement = received.next(BetSettlement.class);
            assertThat(settlement.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(settlement.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(settlement.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(settlement.getMarkets())
                    .as("markets")
                    .extracting(Market::getId, Market::getSpecifiers)
                    .containsExactly(tuple(1, Map.of()), tuple(42, Map.of("setnr", "1")));

            MarketWithSettlement winner = settlement.getMarkets().get(0);
            assertThat(winner.getOutcomeSettlements())
                    .as("outcomes of market 1")
                    .extracting(Outcome::getId, OutcomeSettlement::getOutcomeResult, OutcomeSettlement::getVoidFactor)
                    .containsExactly(tuple("1", OutcomeResult.WON, null), tuple("2", OutcomeResult.LOST, null));
            // the market description comes over REST; the settlement carries only the ids
            assertThat(winner.getName()).as("name of market 1").isEqualTo("Winner");

            assertThat(settlement.getMarkets().get(1).getOutcomeSettlements())
                    .as("outcomes of market 42")
                    .extracting(Outcome::getId, OutcomeSettlement::getOutcomeResult, OutcomeSettlement::getVoidFactor)
                    .containsExactly(tuple("1", OutcomeResult.WON, VoidFactor.REFUND_HALF));
        }
    }

    @Test
    void aRollbackBetSettlementReachesOnRollbackBetSettlement() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent =
                    Sent.publishing(feed, fromLiveProducer("feed/rollback_bet_settlement/rollback_bet_settlement.xml"));

            RollbackBetSettlement<?> rollback = received.next(RollbackBetSettlement.class);
            assertThat(rollback.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(rollback.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(rollback.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(rollback.getMarkets())
                    .as("markets")
                    .extracting(Market::getId, Market::getSpecifiers)
                    .containsExactly(tuple(1, Map.of()), tuple(2, Map.of("mapnr", "2")));
        }
    }

    @Test
    @SuppressWarnings("deprecation") // KD-5 reads the deprecated void reason on purpose
    void aBetCancelReachesOnBetCancelWithItsWindowAndVoidReasons() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent = Sent.publishing(feed, Fixtures.read("feed/bet_cancel/bet_cancel.xml"));

            BetCancel<?> cancel = received.next(BetCancel.class);
            assertThat(cancel.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(cancel.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(cancel.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(cancel.getStartTime())
                    .as("start of the cancelled window")
                    .isEqualTo(WINDOW_START);
            assertThat(cancel.getEndTime()).as("end of the cancelled window").isEqualTo(WINDOW_END);
            assertThat(cancel.getMarkets())
                    .as("markets")
                    .extracting(
                            Market::getId,
                            Market::getSpecifiers,
                            MarketCancel::getVoidReasonId,
                            MarketCancel::getVoidReasonParams)
                    .containsExactly(
                            tuple(1, Map.of(), null, null),
                            tuple(42, Map.of("setnr", "1"), null, null),
                            tuple(17, Map.of("mapnr", "2"), 4, "minutes=5"));
            // market 17 carries void_reason="1" as well; 0.0.x never reads it
            KnownDifference.VOID_REASON_IS_ALWAYS_NULL.expectLegacy(
                    () -> assertThat(cancel.getMarkets().get(2).getVoidReason())
                            .as("void reason of market 17")
                            .isNull());
        }
    }

    @Test
    void aRollbackBetCancelReachesOnRollbackBetCancel() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent = Sent.publishing(feed, fromLiveProducer("feed/rollback_bet_cancel/rollback_bet_cancel.xml"));

            RollbackBetCancel<?> rollback = received.next(RollbackBetCancel.class);
            assertThat(rollback.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(rollback.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(rollback.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(rollback.getStartTime())
                    .as("start of the restored window")
                    .isEqualTo(WINDOW_START);
            assertThat(rollback.getEndTime()).as("end of the restored window").isEqualTo(WINDOW_END);
            assertThat(rollback.getMarkets())
                    .as("markets")
                    .extracting(Market::getId, Market::getSpecifiers)
                    .containsExactly(tuple(1, Map.of()));
        }
    }

    @Test
    void aFixtureChangeReachesOnFixtureChange() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent sent = Sent.publishing(feed, Fixtures.read("feed/fixture_change/fixture_change.xml"));

            FixtureChange<?> change = received.next(FixtureChange.class);
            assertThat(change.getEvent().getId()).as("event").isEqualTo(MATCH);
            assertThat(change.getProducer().getId()).as("producer").isEqualTo(2);
            assertThat(change.getTimestamp().getCreated()).as("created").isBetween(sent.from(), sent.to());
            assertThat(change.getChangeType()).as("change type").isEqualTo(FixtureChangeType.NEW);
            assertThat(change.getNextLiveTime())
                    .as("next live time, which the fixture leaves out")
                    .isNull();
            // the feed has no start time on a fixture change; 0.0.x reads the absent value as 0
            KnownDifference.FIXTURE_CHANGE_START_TIME_IS_ZERO.expect(
                    () -> assertThat(change.getStartTime()).as("start time").isEqualTo(new Date(0)),
                    () -> assertThat(change.getStartTime()).as("start time").isNull());
        }
    }

    /**
     * A bet stop for some market groups names them separated by a pipe. 0.0.x splits the attribute
     * on the two characters {@code \|} rather than on the pipe, and hands back one group.
     */
    @Test
    void aBetStopForSeveralGroupsNamesEach() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            Sent.publishing(
                    feed,
                    Fixtures.replace(
                            Fixtures.read("feed/bet_stop/bet_stop_all_groups.xml"),
                            "groups=\"all\"",
                            "groups=\"winner|handicap\""));

            BetStop<?> betStop = received.next(BetStop.class);
            assertThat(betStop.getEvent().getId()).as("event").isEqualTo(MATCH);
            KnownDifference.PIPE_SEPARATED_LISTS_ARE_NOT_SPLIT.expectLegacy(
                    () -> assertThat(betStop.getGroups()).as("groups").containsExactly("winner|handicap"));
        }
    }

    /**
     * The fixture as producer 2 sends it. The fixtures are schema samples, and 3 is just a valid
     * number there; the producers fixture - what the REST fake answers - lists only 1 and 2. Adding
     * a producer 3 to it would mean inventing its name, scope and API address, all of which the SDK
     * acts on, so the message changes instead.
     */
    private static String fromLiveProducer(String fixture) {
        String message = Fixtures.read(fixture);
        assertThat(message).as(fixture).contains("product=\"3\"");
        return message.replace("product=\"3\"", "product=\"2\"");
    }

    /** When a message went out: the fake stamps it with the current time, somewhere in here. */
    private record Sent(long from, long to) {

        static Sent publishing(FakeFeed feed, String message) {
            long from = System.currentTimeMillis();
            assertThat(feed.publish(message)).as("routed to the SDK's queue").isTrue();
            return new Sent(from, System.currentTimeMillis());
        }
    }
}
