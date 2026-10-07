package com.oddin.oddsfeedsdk.internal.dispatch;

import static com.oddin.oddsfeedsdk.internal.message.MessageWorld.MATCH;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.api.entities.sportevent.MatchStatus;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Tournament;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.amqp.SessionQueue;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.message.MessageWorld;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.EventMessage;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A session's dispatcher, one delivery at a time, over the real decoder, message factory and caches
 * against the fake API: which callback each fixture reaches, what is acknowledged, what the
 * recovery actor hears, what the caches hold when the callback runs, and that nothing a step or a
 * callback throws stops the session.
 */
class SessionDispatcherTest {

    private static final long WAIT_SECONDS = 10;
    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String WITH_STATUS = "feed/odds_change/odds_change_closed_with_winner.xml";
    private static final String BET_STOP = "feed/bet_stop/bet_stop_all_groups.xml";
    private static final String FIXTURE_CHANGE = "feed/fixture_change/fixture_change.xml";
    private static final String SUMMARY = "/v1/sports/en/sport_events/" + MATCH + "/summary";
    private static final String TOURNAMENT = "od:tournament:1042";

    private final MessageWorld world = MessageWorld.start();
    private final Failures failures = new Failures();
    private final EventsDispatcher events = new EventsDispatcher(failures, null, world.producers::getProducer);
    private final ClockOffsets offsets = new ClockOffsets(world.producers);
    private final FixtureChanges fixtureChanges = new FixtureChanges();
    private final Transport transport = new Transport();
    private final Listener listener = new Listener();
    private final Ext ext = new Ext();
    private final Facts facts = new Facts();
    private final OddsFeedSession session = new OddsFeedSession() {};
    private long tag;
    private Instant lastReceived = Instant.EPOCH;

    SessionDispatcherTest() {
        events.start();
    }

    @AfterEach
    void close() {
        events.close();
        world.close();
    }

    // ---- every message family reaches its callback, and is acknowledged

    @Test
    void eachMessageReachesItsCallbackWithTheSessionAndIsAcknowledged() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        Instant receivedFirst = lastReceived;
        handle(dispatcher, Fixtures.read(BET_STOP));
        handle(dispatcher, Fixtures.read("feed/bet_settlement/bet_settlement.xml"));
        handle(dispatcher, live("feed/rollback_bet_settlement/rollback_bet_settlement.xml"));
        handle(dispatcher, Fixtures.read("feed/bet_cancel/bet_cancel.xml"));
        handle(dispatcher, live("feed/rollback_bet_cancel/rollback_bet_cancel.xml"));
        handle(dispatcher, Fixtures.read(FIXTURE_CHANGE));

        assertThat(listener.messages)
                .extracting(Received::callback)
                .containsExactly(
                        "onOddsChange",
                        "onBetStop",
                        "onBetSettlement",
                        "onRollbackBetSettlement",
                        "onBetCancel",
                        "onRollbackBetCancel",
                        "onFixtureChange");
        assertThat(listener.messages).extracting(Received::session).containsOnly(session);
        assertThat(listener.messages)
                .extracting(received ->
                        ((EventMessage<?>) received.message()).getEvent().getId())
                .containsOnly(MATCH);
        assertThat(transport.acked).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(dispatcher.handled()).isEqualTo(7);
        assertThat(failures.failures).isEmpty();

        var oddsChange = (OddsChange<?>) listener.messages.getFirst().message();
        assertThat(oddsChange.getMarkets()).hasSize(3);
        MessageTimestamp timestamp = oddsChange.getTimestamp();
        assertThat(timestamp.getCreated()).isEqualTo(1777832981632L);
        assertThat(timestamp.getReceived()).isEqualTo(receivedFirst.toEpochMilli());
        assertThat(timestamp.getSent()).isEqualTo(receivedFirst.toEpochMilli() - 500);
        assertThat(timestamp.getPublished()).isPositive();
        assertThat(oddsChange.getRawMessage())
                .isEqualTo(Fixtures.read(ODDS_CHANGE).getBytes(StandardCharsets.UTF_8));
    }

    /** KD-5 and KD-6, through the session. */
    @Test
    @SuppressWarnings("deprecation") // KD-5 reads the deprecated void reason on purpose
    void aCancelCarriesItsVoidReasonAndABetStopItsGroupsSplit() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read("feed/bet_cancel/bet_cancel.xml"));
        handle(dispatcher, Fixtures.replace(Fixtures.read(BET_STOP), "groups=\"all\"", "groups=\"winner|handicap\""));
        var cancel = (BetCancel<?>) listener.messages.get(0).message();
        assertThat(cancel.getMarkets()).extracting(MarketCancel::getVoidReason).containsExactly(null, null, "1");
        var stop = (BetStop<?>) listener.messages.get(1).message();
        assertThat(stop.getGroups()).containsExactly("winner", "handicap");
    }

    @Test
    void theExtendedListenerGetsEveryMessageDecodedBeforeTheSessionsCallback() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.LIVE_ONLY);
        handle(dispatcher, Fixtures.read("feed/alive/alive.xml"));
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(ext.calls)
                .containsExactly(
                        "onRawFeedMessageReceived OFAlive LIVE_ONLY -.-.-.alive.-.-.-.-",
                        "onRawFeedMessageBytes alive LIVE_ONLY",
                        "onRawFeedMessageReceived OFOddsChange LIVE_ONLY hi.-.live.odds_change.-.od:match.198314.-",
                        "onRawFeedMessageBytes odds_change LIVE_ONLY",
                        "onOddsChange");
        assertThat(ext.created)
                .as("each its own timestamp, which the one before cannot change")
                .containsExactly(1777832981632L, 1777832981632L, 1777832981632L, 1777832981632L);
        assertThat(((OddsChange<?>) listener.messages.getFirst().message())
                        .getTimestamp()
                        .getCreated())
                .isEqualTo(1777832981632L);
    }

    // ---- the recovery actor's facts

    @Test
    void theRecoveryActorHearsOfEveryMessageAliveAndSnapshotCompleteTheSessionFinished() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        handle(dispatcher, Fixtures.read(BET_STOP));
        handle(dispatcher, FeedMessages.alive(2, false));
        handle(dispatcher, FeedMessages.snapshotComplete(2, 712));

        assertThat(facts.facts)
                .containsExactly(
                        "processed 2 1777832981632 request=2049987833",
                        "processed 2 1777832981632 request=0",
                        "alive 2 1777832981632 subscribed=false",
                        "snapshotComplete 2 712");
        assertThat(facts.takenAt).allSatisfy(at -> assertThat(at).isPositive());
        assertThat(listener.messages)
                .as("alives and snapshot completes reach no callback")
                .hasSize(2);
        assertThat(transport.acked).hasSize(4);
    }

    @Test
    void aReplaySessionAndAClientsAliveOnlySessionPostNoFacts() {
        handle(dispatcher(MessageInterest.ALL, true), Fixtures.read(ODDS_CHANGE));
        handle(dispatcher(MessageInterest.SYSTEM_ALIVE_ONLY, false), FeedMessages.alive(2, true));
        handle(dispatcher(MessageInterest.SYSTEM_ALIVE_ONLY, false), FeedMessages.snapshotComplete(2, 712));
        assertThat(facts.facts).isEmpty();
        assertThat(listener.messages).as("the replay's message").hasSize(1);
    }

    // ---- the filter

    @Test
    void aMessageOfAProducerTheListDoesNotHaveIsDroppedAndAcknowledged() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(BET_STOP).replace("product=\"2\"", "product=\"7\""));
        assertThat(listener.messages).isEmpty();
        assertThat(facts.facts).isEmpty();
        assertThat(transport.acked).hasSize(1);
        assertThat(dispatcher.unknownProducers()).isEqualTo(1);
    }

    @Test
    void aMessageOfADisabledProducerOrOneOutsideTheInterestIsDropped() {
        SessionDispatcher prematch = dispatcher(MessageInterest.PREMATCH_ONLY);
        handle(prematch, Fixtures.read(BET_STOP));
        handle(prematch, withStatus("5", System.currentTimeMillis()));
        assertThat(listener.messages).as("producer 2 is live only").isEmpty();

        world.producers.setProducerState(2, false);
        SessionDispatcher all = dispatcher(MessageInterest.ALL);
        handle(all, Fixtures.read(BET_STOP));
        handle(all, withStatus("5", System.currentTimeMillis()));
        assertThat(listener.messages).as("producer 2 disabled").isEmpty();
        assertThat(transport.acked).hasSize(4);
        assertThat(facts.facts).as("nothing the recovery actor counts").isEmpty();

        world.producers.setProducerState(2, true);
        handle(all, Fixtures.read(BET_STOP));
        assertThat(status((EventMessage<?>) listener.messages.getFirst().message()))
                .as("the summary's status: neither dropped odds change wrote its own")
                .isEqualTo(EventStatus.Finished);
    }

    @Test
    void aFixtureChangeDeliveredOnceInAnySessionIsNotDeliveredAgain() {
        SessionDispatcher hi = dispatcher(MessageInterest.HI_PRIORITY_ONLY);
        SessionDispatcher all = dispatcher(MessageInterest.ALL);
        String change = FeedMessages.stampedAt(Fixtures.read(FIXTURE_CHANGE), 1_000);
        handle(hi, change);
        handle(all, change);
        handle(hi, change);
        handle(all, FeedMessages.stampedAt(change, 1_001));
        assertThat(listener.messages)
                .extracting(received -> received.message().getTimestamp().getCreated())
                .containsExactly(1_000L, 1_001L);
        assertThat(hi.repeatedFixtureChanges() + all.repeatedFixtureChanges()).isEqualTo(2);
        assertThat(transport.acked).hasSize(4);
    }

    @Test
    void aFixtureChangeWhoseEventIdIsNoUrnIsReportedAndNotRemembered() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        String key = "hi.-.live.fixture_change.-.od:match.198314.-";
        handle(dispatcher, Fixtures.read(FIXTURE_CHANGE).replace("od:match:198314", "x".repeat(16_384)), key);
        assertThat(failures.next().callback()).isEqualTo("cache write");
        assertThat(listener.messages).isEmpty();
        assertThat(fixtureChanges.size()).as("remembered").isZero();
        assertThat(transport.acked).hasSize(1);
    }

    // ---- what the callback reads from the caches

    @Test
    void aFixtureChangeInvalidatesTheMatchBeforeItsCallbackReadsIt() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(BET_STOP));
        var match = (Match) ((BetStop<?>) listener.messages.getFirst().message()).getEvent();
        assertThat(match.getName(Locale.ENGLISH)).isEqualTo("Team Alpha vs Team Beta");
        world.api.respond(
                SUMMARY,
                200,
                Fixtures.read("rest/match_summary/match_summary.xml")
                        .replace("name=\"Team Alpha vs Team Beta\"", "name=\"Team Alpha vs Team Gamma\""));

        listener.onFixture = message -> listener.read.add(((Match) message.getEvent()).getName(Locale.ENGLISH));
        handle(dispatcher, Fixtures.read(FIXTURE_CHANGE));
        assertThat(listener.read).containsExactly("Team Alpha vs Team Gamma");
    }

    @Test
    void aFixtureChangeOfATournamentInvalidatesTheTournamentBeforeItsCallbackReadsIt() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(
                dispatcher,
                Fixtures.read(BET_STOP).replace("od:match:198314", TOURNAMENT),
                "hi.-.live.bet_stop.5.od:tournament.1042.-");
        var tournament = (Tournament) ((BetStop<?>) listener.messages.getFirst().message()).getEvent();
        assertThat(tournament.getName(Locale.ENGLISH)).isEqualTo("Test Tournament");
        world.api.respond(
                "/v1/sports/en/tournaments/" + TOURNAMENT + "/info",
                200,
                Fixtures.read("rest/tournament_info/tournament_info.xml")
                        .replace("name=\"Test Tournament\"", "name=\"Renamed Tournament\""));

        listener.onFixture = message -> listener.read.add(((Tournament) message.getEvent()).getName(Locale.ENGLISH));
        handle(
                dispatcher,
                Fixtures.read(FIXTURE_CHANGE).replace("od:match:198314", TOURNAMENT),
                "hi.-.live.fixture_change.5.od:tournament.1042.-");
        assertThat(listener.read).containsExactly("Renamed Tournament");
    }

    @Test
    void anOddsChangesStatusIsInTheMatchWhenItsCallbackRuns() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> listener.read.add(status(message));
        handle(dispatcher, withStatus("5", System.currentTimeMillis()));
        handle(dispatcher, withStatus("99", System.currentTimeMillis() + 1));
        handle(dispatcher, withoutStatusAttribute(System.currentTimeMillis() + 2));
        assertThat(listener.read)
                .as("every status its own, a number the SDK does not know and a missing attribute Unknown")
                .containsExactly(EventStatus.Cancelled, EventStatus.Unknown, EventStatus.Unknown);
    }

    @Test
    void anOlderMessageDoesNotReplaceTheStatusOfANewerOne() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> listener.read.add(status(message));
        long now = System.currentTimeMillis();
        handle(dispatcher, withStatus("5", now));
        handle(dispatcher, withStatus("1", now - 5_000));
        assertThat(listener.read).as("KD-13").containsExactly(EventStatus.Cancelled, EventStatus.Cancelled);
        assertThat(listener.messages).as("both delivered").hasSize(2);
    }

    /** KD-14: the age is the producer's own, by the offset its alives show. */
    @Test
    void aMessageOlderThanTheStatusAgeByItsProducersClockWritesNoStatus() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> listener.read.add(status(message));
        long halfAnHourAgo = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
        handle(dispatcher, withStatus("5", halfAnHourAgo));
        assertThat(listener.read).as("from the summary, which says closed").containsExactly(EventStatus.Finished);

        // a producer whose clock is half an hour behind the SDK's sent it just now
        offsets.alive(2, halfAnHourAgo, System.currentTimeMillis());
        handle(dispatcher, withStatus("5", halfAnHourAgo + 1));
        assertThat(listener.read).last().isEqualTo(EventStatus.Cancelled);
    }

    @Test
    void aReplaysOldMessagesWriteTheStatusAsCurrent() {
        SessionDispatcher replay = dispatcher(MessageInterest.ALL, true);
        listener.onOdds = message -> listener.read.add(status(message));
        handle(
                replay,
                withStatus("5", System.currentTimeMillis() - Duration.ofDays(1).toMillis()));
        assertThat(listener.read).containsExactly(EventStatus.Cancelled);
    }

    @Test
    void aReplayPlayedAgainWritesTheStatusOfEachRun() {
        SessionDispatcher replay = dispatcher(MessageInterest.ALL, true);
        listener.onOdds = message -> listener.read.add(status(message));
        long dayAgo = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        handle(replay, withStatus("1", dayAgo));
        handle(replay, withStatus("5", dayAgo + 60_000));
        // the same match again, with the timestamps it had the first time
        handle(replay, withStatus("1", dayAgo));
        assertThat(listener.read).containsExactly(EventStatus.Live, EventStatus.Cancelled, EventStatus.Live);
    }

    // ---- the failure policy

    @Test
    void aCallbackThatThrowsIsReportedAndTheNextMessageArrives() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> {
            throw new IllegalStateException("the client's bug");
        };
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        handle(dispatcher, Fixtures.read(BET_STOP));
        assertThat(listener.messages).extracting(Received::callback).containsExactly("onOddsChange", "onBetStop");
        assertThat(listener.unparsable).as("KD-9: not reported as unparsable").isEmpty();
        CallbackFailure failure = failures.next();
        assertThat(failure.callback()).isEqualTo("onOddsChange");
        assertThat(failure.clientCode()).isTrue();
        assertThat(failure.session()).isSameAs(session);
        assertThat(failure.exception()).hasMessage("the client's bug");
        assertThat(transport.acked).hasSize(2);
        assertThat(facts.facts).hasSize(2);
        assertThat(dispatcher.callbackFailures()).isEqualTo(1);
    }

    @Test
    void aRawCallbackThatThrowsIsReportedAndTheMessageGoesOn() throws InterruptedException {
        for (String callback : List.of("onRawFeedMessageReceived", "onRawFeedMessageBytes")) {
            SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
            ext.calls.clear();
            facts.facts.clear();
            ext.throwing = callback;
            handle(dispatcher, Fixtures.read(ODDS_CHANGE));
            assertThat(ext.calls)
                    .as(callback + " throwing")
                    .containsExactly(
                            "onRawFeedMessageReceived OFOddsChange ALL hi.-.live.odds_change.-.od:match.198314.-",
                            "onRawFeedMessageBytes odds_change ALL",
                            "onOddsChange");
            CallbackFailure failure = failures.next();
            assertThat(failure.callback()).isEqualTo(callback);
            assertThat(failure.clientCode()).isTrue();
            assertThat(failure.exception()).hasMessage("the client's bug in " + callback);
            assertThat(facts.facts).as("finished all the same").hasSize(1);
            assertThat(dispatcher.callbackFailures()).isEqualTo(1);
        }
        assertThat(transport.acked).hasSize(2);
        assertThat(listener.unparsable).as("KD-9").isEmpty();
    }

    @Test
    void anErrorFromACallbackDoesNotStopTheSession() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> {
            throw new AssertionError("an error of the client's");
        };
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        handle(dispatcher, Fixtures.read(BET_STOP));
        assertThat(listener.messages).hasSize(2);
        assertThat(transport.acked).hasSize(2);
    }

    @Test
    void aBodyThatDoesNotDecodeIsUnparsableAndAcknowledged() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        byte[] broken = "<odds_change product=\"2\"".getBytes(StandardCharsets.UTF_8);
        dispatcher.handle(delivery(broken, "hi.-.live.odds_change.-.od:match.198314.-"));
        UnparsableMessage<?> unparsable = requireNonNull(listener.unparsable.poll());
        assertThat(unparsable.getEvent().getId()).isEqualTo(MATCH);
        assertThat(unparsable.getRawMessage()).isEqualTo(broken);
        assertThat(unparsable.getTimestamp().getCreated()).isZero();
        CallbackFailure failure = failures.next();
        assertThat(failure.callback()).isEqualTo("decode");
        assertThat(failure.clientCode()).isFalse();
        assertThat(transport.acked).hasSize(1);
        assertThat(facts.facts).isEmpty();
        assertThat(dispatcher.unparsable()).isEqualTo(1);
    }

    @Test
    void aBodyOverTheMaximumSizeIsUnparsableWithoutItsBytes() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        dispatcher.handle(new RawDelivery(
                null, 2 << 20, "hi.-.live.odds_change.-.od:match.198314.-", ++tag, 0, Instant.ofEpochMilli(1), null));
        UnparsableMessage<?> unparsable = requireNonNull(listener.unparsable.poll());
        assertThat(unparsable.getRawMessage()).isNull();
        assertThat(dispatcher.oversized()).isEqualTo(1);
        assertThat(transport.acked).hasSize(1);
    }

    @Test
    void anUnparsableMessageWhoseRoutingKeyNamesNoEventIsAcknowledgedOnly() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        dispatcher.handle(delivery("<alive".getBytes(StandardCharsets.UTF_8), "-.-.-.alive.-.-.-.-"));
        assertThat(listener.unparsable).isEmpty();
        assertThat(transport.acked).hasSize(1);
    }

    @Test
    void aMessageTheSdkCannotBuildReachesNoCallbackAndIsReported() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(BET_STOP), "hi.-.live.bet_stop.-.od:tournament.7.-");
        assertThat(listener.messages).isEmpty();
        CallbackFailure failure = failures.next();
        assertThat(failure.callback()).isEqualTo("build");
        assertThat(failure.clientCode()).isFalse();
        assertThat(transport.acked).hasSize(1);
        assertThat(facts.facts).as("the session finished it all the same").hasSize(1);
    }

    @Test
    void aMessageTheSdkCannotBuildStillWritesItsStatus() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        // the body's match, a routing key's event the SDK cannot build: a tournament without its sport
        handle(dispatcher, withStatus("5", System.currentTimeMillis()), "hi.-.live.odds_change.-.od:tournament.7.-");
        assertThat(listener.messages).isEmpty();
        assertThat(failures.next().callback()).isEqualTo("build");

        handle(dispatcher, Fixtures.read(BET_STOP));
        assertThat(status((EventMessage<?>) listener.messages.getFirst().message()))
                .as("written before the build")
                .isEqualTo(EventStatus.Cancelled);
    }

    @Test
    void aCacheWriteThatFailsReachesNoCallbackAndIsReported() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(
                dispatcher,
                withStatus("5", System.currentTimeMillis()).replace("event_id=\"od:match:198314\"", "event_id=\"bad\""),
                "hi.-.live.odds_change.-.od:match.198314.-");
        assertThat(listener.messages).isEmpty();
        assertThat(failures.next().callback()).isEqualTo("cache write");
        assertThat(transport.acked).hasSize(1);
        handle(dispatcher, Fixtures.read(BET_STOP));
        assertThat(listener.messages).hasSize(1);
    }

    @Test
    void anInterruptACallbackLeavesIsCleared() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        listener.onOdds = message -> Thread.currentThread().interrupt();
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    // ---- helpers

    private SessionDispatcher dispatcher(MessageInterest interest) {
        return dispatcher(interest, false);
    }

    private SessionDispatcher dispatcher(MessageInterest interest, boolean replay) {
        var pipeline = new Pipeline(
                world.decoder,
                world.messages,
                world.matches,
                world.profiles,
                world.producers,
                fixtureChanges,
                offsets,
                events,
                InstantSource.system());
        return new SessionDispatcher(1, session, interest, listener, ext, transport, facts, replay, pipeline);
    }

    private void handle(SessionDispatcher dispatcher, String xml) {
        handle(dispatcher, xml, FakeFeed.routingKey(xml));
    }

    private void handle(SessionDispatcher dispatcher, String xml, String routingKey) {
        dispatcher.handle(delivery(xml.getBytes(StandardCharsets.UTF_8), routingKey));
    }

    private RawDelivery delivery(byte[] body, String routingKey) {
        // received now: the live state takes a feed write as the feed's for the match status age from then
        Instant receivedAt = Instant.now();
        lastReceived = receivedAt;
        return new RawDelivery(body, body.length, routingKey, ++tag, 0, receivedAt, receivedAt.minusMillis(500));
    }

    private static String live(String fixture) {
        return MessageWorld.fromLiveProducer(Fixtures.read(fixture));
    }

    private static String withStatus(String status, long timestamp) {
        return FeedMessages.stampedAt(
                Fixtures.read(WITH_STATUS).replace("status=\"4\"", "status=\"" + status + "\""), timestamp);
    }

    private static String withoutStatusAttribute(long timestamp) {
        return FeedMessages.stampedAt(Fixtures.read(WITH_STATUS).replace(" status=\"4\"", ""), timestamp);
    }

    private static @Nullable EventStatus status(EventMessage<?> message) {
        MatchStatus status = ((Match) message.getEvent()).getStatus();
        return status == null ? null : status.getStatus();
    }

    /** One callback, with what it got. */
    private record Received(String callback, OddsFeedSession session, Message message) {}

    /** Records the session's callbacks; can be told to read or throw in some of them. */
    private final class Listener implements OddsFeedListener {
        final List<Received> messages = new CopyOnWriteArrayList<>();
        final BlockingQueue<UnparsableMessage<?>> unparsable = new LinkedBlockingQueue<>();
        final List<@Nullable Object> read = new ArrayList<>();
        volatile Consumer<OddsChange<?>> onOdds = message -> {};
        volatile Consumer<FixtureChange<?>> onFixture = message -> {};

        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            heard("onOddsChange", session, message);
            onOdds.accept(message);
        }

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
            heard("onBetStop", session, message);
        }

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
            heard("onBetSettlement", session, message);
        }

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
            heard("onRollbackBetSettlement", session, message);
        }

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
            heard("onRollbackBetCancel", session, message);
        }

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
            heard("onBetCancel", session, message);
        }

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
            heard("onFixtureChange", session, message);
            onFixture.accept(message);
        }

        @Override
        public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {
            unparsable.add(message);
        }

        private void heard(String callback, OddsFeedSession session, Message message) {
            messages.add(new Received(callback, session, message));
            ext.calls.add(callback);
        }
    }

    /** Records the raw callbacks. */
    private static final class Ext implements OddsFeedExtListener {
        final List<String> calls = new CopyOnWriteArrayList<>();

        final List<Long> created = new CopyOnWriteArrayList<>();
        /** The raw callback that throws, if one does. */
        volatile @Nullable String throwing;

        @Override
        public void onRawFeedMessageReceived(
                UnparsedMessage message,
                MessageInterest messageInterest,
                RoutingKeyInfo routingKey,
                MessageTimestamp timestamp) {
            created.add(timestamp.getCreated());
            // a client may change what it is given
            timestamp.setCreated(-1);
            calls.add("onRawFeedMessageReceived " + message.getClass().getSimpleName() + " " + messageInterest + " "
                    + routingKey.getFullRoutingKey());
            throwIf("onRawFeedMessageReceived");
        }

        @Override
        public void onRawFeedMessageBytes(
                byte[] body, MessageInterest messageInterest, RoutingKeyInfo routingKey, MessageTimestamp timestamp) {
            created.add(timestamp.getCreated());
            String xml = new String(body, StandardCharsets.UTF_8);
            calls.add("onRawFeedMessageBytes " + xml.substring(1, xml.indexOf(' ')) + " " + messageInterest);
            throwIf("onRawFeedMessageBytes");
        }

        private void throwIf(String callback) {
            if (callback.equals(throwing)) {
                throw new IllegalStateException("the client's bug in " + callback);
            }
        }

        @Override
        public void onRawApiDataReceived(URI uri, Object data) {}
    }

    /** Records the facts the recovery actor would get. */
    private static final class Facts implements SessionFacts {
        final List<String> facts = new CopyOnWriteArrayList<>();
        final List<Long> takenAt = new CopyOnWriteArrayList<>();

        @Override
        public void processed(long producerId, long generatedAt, long takenAt, long requestId) {
            facts.add("processed " + producerId + " " + generatedAt + " request=" + requestId);
            this.takenAt.add(takenAt);
        }

        @Override
        public void alive(long producerId, long generatedAt, long takenAt, boolean subscribed) {
            facts.add("alive " + producerId + " " + generatedAt + " subscribed=" + subscribed);
            this.takenAt.add(takenAt);
        }

        @Override
        public void snapshotComplete(long producerId, long requestId) {
            facts.add("snapshotComplete " + producerId + " " + requestId);
        }

        @Override
        public void channelLost() {
            facts.add("channelLost");
        }

        @Override
        public void channelReopened() {
            facts.add("channelReopened");
        }

        @Override
        public void closed() {
            facts.add("closed");
        }
    }

    /** Records the acknowledgements. */
    private static final class Transport implements SessionTransport {
        final List<Long> acked = new CopyOnWriteArrayList<>();
        private final SessionQueue queue = new SessionQueue(10);

        @Override
        public void ack(RawDelivery delivery) {
            acked.add(delivery.deliveryTag());
        }

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
        public long skippedAcks() {
            return 0;
        }

        @Override
        public SessionQueue queue() {
            return queue;
        }
    }

    /** The global listener, as far as failures go. */
    private static final class Failures implements GlobalEventsListener {
        final BlockingQueue<CallbackFailure> failures = new LinkedBlockingQueue<>();

        CallbackFailure next() throws InterruptedException {
            return requireNonNull(failures.poll(WAIT_SECONDS, TimeUnit.SECONDS), "a failure reported");
        }

        @Override
        public void onCallbackFailure(CallbackFailure failure) {
            failures.add(failure);
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
