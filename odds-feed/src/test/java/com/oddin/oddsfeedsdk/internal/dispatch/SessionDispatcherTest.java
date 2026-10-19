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
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.amqp.Queues;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.amqp.SessionQueue;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.cache.LiveState;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.oddin.oddsfeedsdk.internal.clock.SplitClock;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private final AtomicBoolean feedClosed = new AtomicBoolean();
    private final OddsFeedSession session = new OddsFeedSession() {};
    private FeedClock clock = FeedClock.system();
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
        assertThat(facts.taken).hasSize(3).allSatisfy(at -> {
            assertThat(at.wall()).isPositive();
            assertThat(at.mono()).isPositive();
        });
        assertThat(listener.messages)
                .as("alives and snapshot completes reach no callback")
                .hasSize(2);
        assertThat(transport.acked).hasSize(4);
    }

    @Test
    void everyFactCarriesBothReadingsOfTheFeedClockFromWhenTheDispatcherTookTheDelivery() {
        var wall = new AtomicLong(5_000);
        var nanos = new AtomicLong(-3_000_000_000L);
        // a monotonic origin of its own, apart from the wall one: the two readings are never the same number
        clock = FeedClock.of(() -> Instant.ofEpochMilli(wall.get()), nanos::get);
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        // the clock moves while the facts are posted: the stamp is the dispatcher's, not the post's
        facts.beforePost = () -> {
            wall.addAndGet(100);
            nanos.addAndGet(100_000_000L);
        };

        nanos.addAndGet(2_000_000_000L);
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        wall.set(9_000);
        nanos.set(10_000_000_000L);
        handle(dispatcher, FeedMessages.alive(2, true));

        assertThat(facts.taken).containsExactly(new Stamp(5_000, 2_001), new Stamp(9_000, 13_001));
    }

    /** The marks are the SDK's: the client's message keeps the stamp the producer gave it. */
    @Test
    void theRecoveryActorTakesAMessageStampedAheadOfItsProducerAtTheProducersNowAtReceipt() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        handle(dispatcher, Fixtures.read(BET_STOP));
        long ahead = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        handle(dispatcher, FeedMessages.stampedAt(Fixtures.read(BET_STOP), ahead));
        long received = lastReceived.toEpochMilli();
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), ahead));

        assertThat(facts.facts)
                .containsExactly(
                        "processed 2 1777832981632 request=0",
                        "processed 2 " + ahead + " request=0",
                        "alive 2 " + ahead + " subscribed=true");
        assertThat(facts.marks.get(0)).as("not ahead: its own stamp").isEqualTo(1777832981632L);
        assertThat(facts.marks.get(1))
                .as("ahead, and no alive has shown an offset yet: the SDK's own clock at receipt")
                .isEqualTo(received);
        assertThat(facts.marks.get(2)).isEqualTo(lastReceived.toEpochMilli());
        assertThat(((BetStop<?>) listener.messages.get(1).message())
                        .getTimestamp()
                        .getCreated())
                .as("the client's message keeps its stamp")
                .isEqualTo(ahead);
    }

    @Test
    void theRecoveryActorTakesAStampAheadByNoMoreThanASecondAsItIs() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        long ahead = System.currentTimeMillis() + 500;
        handle(dispatcher, FeedMessages.stampedAt(Fixtures.read(BET_STOP), ahead));
        assertThat(lastReceived.toEpochMilli()).as("received before the stamp").isLessThan(ahead);
        assertThat(facts.marks).containsExactly(ahead);
    }

    /** The first alive of a producer, stamped far ahead, has no offset before it: the channel's mark is the copy's. */
    @Test
    void theSessionsCopyOfAProducersFirstAliveStampedAheadIsMarkedAsTheChannelMarkedIt() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        long now = System.currentTimeMillis();
        long ahead = now + Duration.ofMinutes(10).toMillis();
        // the alive dispatcher first: the mark before its offset is kept, then the offset
        long channel = offsets.markAlive(2, ahead, Stamp.of(now));
        offsets.alive(2, ahead, Stamp.of(now));
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), ahead));
        assertThat(channel).as("no offset yet: the SDK's own clock").isEqualTo(now);
        assertThat(facts.marks)
                .as("the copy takes it, not the offset the alive showed")
                .containsExactly(now);
    }

    @Test
    void theSessionsCopyOfAProducersFirstAliveStampedAheadHandledBeforeTheChannelsIsMarkedByTheSdksClock() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        long ahead = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), ahead));
        long received = lastReceived.toEpochMilli();
        assertThat(facts.marks).as("no offset yet: the SDK's clock at receipt").containsExactly(received);
        // then the channel's, with the same alive, whose offset is kept after its mark
        long channel = offsets.markAlive(2, ahead, Stamp.of(received));
        offsets.alive(2, ahead, Stamp.of(received));
        assertThat(channel).isEqualTo(received);
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), ahead));
        assertThat(facts.marks).as("a second copy, after the channel's").containsExactly(received, received);
    }

    @Test
    void theSessionsCopyOfAnAliveTheChannelGotTwiceIsMarkedByTheLowerOfTheTwo() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        long now = System.currentTimeMillis();
        long stamp = now + 2_000;
        // a resend: the first delivery is 2 s ahead of the SDK's clock, the second finds the stamp current
        long first = offsets.markAlive(2, stamp, Stamp.of(now));
        offsets.alive(2, stamp, Stamp.of(now));
        long second = offsets.markAlive(2, stamp, Stamp.of(stamp));
        offsets.alive(2, stamp, Stamp.of(stamp));
        assertThat(first).isEqualTo(now);
        assertThat(second).isEqualTo(stamp);
        // the session's copy of the first, handled after the second
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), stamp));
        assertThat(facts.marks).as("bounded by the first delivery's receipt").containsExactly(now);
    }

    /** Every session binds the alive key too: its copy comes after the alive dispatcher kept the offset. */
    @Test
    void theSessionsCopyOfAnAliveStampedAheadIsMarkedByTheOffsetBeforeIt() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        long now = System.currentTimeMillis();
        long ahead = now + Duration.ofMinutes(10).toMillis();
        // as the alive dispatcher kept them: an alive of a clock 2.5 s behind, then this one's own offset
        offsets.alive(2, now - 2_500, Stamp.of(now));
        offsets.alive(2, ahead, Stamp.of(now));
        handle(dispatcher, FeedMessages.stampedAt(FeedMessages.alive(2, true), ahead));
        assertThat(facts.marks).containsExactly(lastReceived.toEpochMilli() - 2_500);
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

    /**
     * The live state counts how long ago the feed wrote by the monotonic reading the delivery was
     * received at, not by its wall one: one clock for the dispatcher and the caches, the monotonic
     * reading hours from the wall's, so a write stamped with the wrong one never goes quiet.
     */
    @Test
    void aLiveWriteIsStampedWithTheMonotonicReceiptReadingAndGoesQuietByIt() {
        var split = new SplitClock();
        split.advanceMono(Duration.ofHours(5));
        try (var shared = MessageWorld.start(ExceptionHandlingStrategy.THROW, split.clock())) {
            var pipeline = new Pipeline(
                    shared.decoder,
                    shared.messages,
                    shared.matches,
                    shared.profiles,
                    shared.producers,
                    fixtureChanges,
                    offsets,
                    events,
                    split.clock());
            var dispatcher = new SessionDispatcher(
                    1, session, MessageInterest.ALL, listener, ext, transport, facts, false, feedClosed::get, pipeline);
            listener.onOdds = message -> listener.read.add(status(message));
            String live = FeedMessages.stampedAt(
                    Fixtures.read(WITH_STATUS).replace("status=\"4\"", "status=\"1\""),
                    split.clock().wallMillis());
            var received = Instant.ofEpochMilli(split.clock().wallMillis());
            dispatcher.handle(new RawDelivery(
                    live.getBytes(StandardCharsets.UTF_8),
                    live.length(),
                    FakeFeed.routingKey(live),
                    ++tag,
                    0,
                    received,
                    split.clock().monoMillis(),
                    received.minusMillis(500)));
            assertThat(listener.read).as("the feed's status").containsExactly(EventStatus.Live);
            int summaries = shared.api.requests("GET", SUMMARY).size();

            split.advanceMono(LiveState.STATUS_AGE.minusMinutes(1));
            assertThat(shared.matches.live(MATCH)).isNotNull();
            assertThat(shared.api.requests("GET", SUMMARY))
                    .as("the feed wrote a minute short of the status age ago: still its")
                    .hasSize(summaries);

            split.advanceMono(Duration.ofMinutes(2));
            assertThat(shared.matches.live(MATCH)).isNotNull();
            assertThat(shared.api.requests("GET", SUMMARY))
                    .as("quiet for the status age: REST loads the summary and takes the status over")
                    .hasSize(summaries + 1);
        }
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
        offsets.alive(2, halfAnHourAgo, Stamp.of(System.currentTimeMillis()));
        handle(dispatcher, withStatus("5", halfAnHourAgo + 1));
        assertThat(listener.read).last().isEqualTo(EventStatus.Cancelled);
    }

    /**
     * KD-14 across a step of the SDK's wall clock: the offset an alive measured before it is read as
     * of the message's own reading, so a fresh message is not refused for the step, and an old one
     * is not taken for fresh by it.
     */
    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void theStatusAgeOfAMessageIsNotMovedByAStepOfTheWallClockAfterItsProducersOffsetWasMeasured(long step) {
        var split = new SplitClock();
        split.advanceMono(Duration.ofHours(5));
        try (var shared = MessageWorld.start(ExceptionHandlingStrategy.THROW, split.clock())) {
            var dispatcher = new SessionDispatcher(
                    1,
                    session,
                    MessageInterest.ALL,
                    listener,
                    ext,
                    transport,
                    facts,
                    false,
                    feedClosed::get,
                    new Pipeline(
                            shared.decoder,
                            shared.messages,
                            shared.matches,
                            shared.profiles,
                            shared.producers,
                            fixtureChanges,
                            offsets,
                            events,
                            split.clock()));
            listener.onOdds = message -> listener.read.add(status(message));
            // a producer whose clock is the SDK's: the alive measured no offset
            var alive = split.clock().stamp();
            offsets.alive(2, alive.wall(), alive);
            split.stepWall(Duration.ofMinutes(step));

            // the producer's clock did not step. A message 25 minutes old by it is that old whatever way
            // the wall clock stepped: older than the status age less the minutes the step would add or take
            long producerNow = alive.wall();
            deliver(
                    dispatcher,
                    split,
                    withStatus("1", producerNow - Duration.ofMinutes(25).toMillis()));
            assertThat(listener.read)
                    .as("25 minutes old: no status of its own, the summary's, which says closed")
                    .containsExactly(EventStatus.Finished);

            // and one sent now is as fresh as it was, not older than the status age by the step
            deliver(dispatcher, split, withStatus("5", producerNow));
            assertThat(listener.read).last().as("sent now").isEqualTo(EventStatus.Cancelled);
        }
    }

    // ---- a stamp ahead of its producer does not hold back the correct messages that follow

    /**
     * A message stamped ten minutes ahead, and then the correct ones, whatever the producer's alives
     * show - its clock 2.5 s behind the SDK's, or its alives 3 s or 25 s late: the correct one writes
     * the status the callback reads. Offsets are always established through the alives; an empty map
     * is a different case (below).
     */
    @ParameterizedTest
    @ValueSource(longs = {2_500, 3_000, 25_000})
    void aMessageStampedAheadOfItsProducerDoesNotHoldBackTheStatusOfTheOnesThatFollow(long offset) {
        try (var feed = new OnSplitClock()) {
            feed.alive(offset);
            feed.deliver("5", Duration.ofMinutes(10).toMillis());
            assertThat(listener.read).as("the bad one writes").containsExactly(EventStatus.Cancelled);

            feed.at(1_000);
            feed.deliver("1", 1_000 - offset - 100);
            feed.at(2_000);
            feed.deliver("5", 2_000 - offset - 100);
            feed.at(2_500);
            feed.deliver("1", 2_000 - offset - 600);

            assertThat(listener.read)
                    .as("the correct one after it, the next in order, and a swapped one that is refused")
                    .containsExactly(
                            EventStatus.Cancelled, EventStatus.Live, EventStatus.Cancelled, EventStatus.Cancelled);
        }
    }

    @Test
    void theSwapsOfOrdinaryTrafficAreStillRefusedWhateverTheAlivesShow() {
        for (long offset : new long[] {100, 3_000, 25_000}) {
            listener.read.clear();
            try (var feed = new OnSplitClock()) {
                feed.alive(offset);
                feed.deliver("5", -offset);
                feed.at(2_300);
                feed.deliver("1", -offset - 2_000);
                feed.at(2_400);
                feed.deliver("1", -offset + 20_000);
                assertThat(listener.read)
                        .as("alives %d ms late: the older one, swapped by 2 s, leaves the status", offset)
                        .containsExactly(EventStatus.Cancelled, EventStatus.Cancelled, EventStatus.Live);
            }
        }
    }

    @Test
    void beforeTheFirstAliveABadStampDoesNotHoldBackTheCorrectOnesAndAProducerFarBehindKeepsTheOldRule() {
        try (var feed = new OnSplitClock()) {
            feed.deliver("5", Duration.ofMinutes(10).toMillis());
            feed.at(1_000);
            feed.deliver("1", 1_000 - 50);
            assertThat(listener.read).containsExactly(EventStatus.Cancelled, EventStatus.Live);
        }
        listener.read.clear();
        try (var feed = new OnSplitClock()) {
            feed.deliver("5", Duration.ofMinutes(10).toMillis());
            feed.at(1_000);
            feed.deliver("1", 1_000 - 120_000);
            assertThat(listener.read)
                    .as("a producer 120 s behind with no alive yet: its message is 120 s old, not timely")
                    .containsExactly(EventStatus.Cancelled, EventStatus.Cancelled);
        }
    }

    /**
     * Two sessions, the one that lags handling an older message last: it was received before the
     * newer one, which a session cannot tell from its own queue, and it does not roll the status back.
     */
    @Test
    void aMessageReceivedBeforeTheOneThatWroteIsRefusedWhicheverSessionHandlesItLast() {
        try (var feed = new OnSplitClock()) {
            feed.alive(100);
            feed.at(10_000);
            feed.deliver(feed.dispatcher, "5", Duration.ofMinutes(10).toMillis(), 10_000);
            feed.at(10_100);
            feed.deliver(feed.other, "1", 9_000 - 200, 9_000);
            assertThat(listener.read).containsExactly(EventStatus.Cancelled, EventStatus.Cancelled);

            feed.deliver(feed.other, "1", 10_100 - 200, 10_100);
            assertThat(listener.read)
                    .last()
                    .as("received after it: it takes over")
                    .isEqualTo(EventStatus.Live);
        }
    }

    /**
     * A message that answers a recovery request never takes the status over from a later stamp: a
     * recovery must not move the live state back, and the next live message repairs a bad stamp.
     */
    @Test
    void aMessageThatAnswersARecoveryRequestNeverTakesTheStatusOverFromALaterStamp() {
        try (var feed = new OnSplitClock()) {
            feed.alive(100);
            feed.deliver("5", Duration.ofMinutes(10).toMillis());
            feed.at(1_000);
            feed.deliverAsAnswerToARecovery("1", 1_000 - 200);
            assertThat(listener.read)
                    .as("timely and received after it, as the rule asks, but it answers a request")
                    .containsExactly(EventStatus.Cancelled, EventStatus.Cancelled);

            feed.at(2_000);
            feed.deliver("1", 2_000 - 200);
            assertThat(listener.read)
                    .last()
                    .as("the next live message repairs it")
                    .isEqualTo(EventStatus.Live);
        }
    }

    /** The odds changes and alives of one producer, 2, on a clock the test moves: wall and monotonic together. */
    private final class OnSplitClock implements AutoCloseable {
        final SplitClock split = new SplitClock();
        final MessageWorld shared;
        final SessionDispatcher dispatcher;
        final SessionDispatcher other;
        final long wall0;
        final long mono0;
        private long elapsed;

        OnSplitClock() {
            split.advanceMono(Duration.ofHours(5));
            shared = MessageWorld.start(ExceptionHandlingStrategy.THROW, split.clock());
            var pipeline = new Pipeline(
                    shared.decoder,
                    shared.messages,
                    shared.matches,
                    shared.profiles,
                    shared.producers,
                    fixtureChanges,
                    offsets,
                    events,
                    split.clock());
            dispatcher = new SessionDispatcher(
                    1, session, MessageInterest.ALL, listener, ext, transport, facts, false, feedClosed::get, pipeline);
            other = new SessionDispatcher(
                    2, session, MessageInterest.ALL, listener, ext, transport, facts, false, feedClosed::get, pipeline);
            wall0 = split.clock().wallMillis();
            mono0 = split.clock().monoMillis();
            listener.onOdds = message -> listener.read.add(status(message));
        }

        /** Moves to {@code millis} after the start. */
        void at(long millis) {
            split.advance(Duration.ofMillis(millis - elapsed));
            elapsed = millis;
        }

        /** An alive of the producer received now, generated {@code offset} before: its clock that far behind. */
        void alive(long offset) {
            offsets.alive(2, wall0 + elapsed - offset, new Stamp(wall0 + elapsed, mono0 + elapsed));
        }

        /** A live odds change with this status, stamped {@code stamp} after the start, received now. */
        void deliver(String status, long stamp) {
            deliver(dispatcher, status, stamp, elapsed);
        }

        void deliver(SessionDispatcher to, String status, long stamp, long receivedAt) {
            deliver(to, withStatus(status, wall0 + stamp), receivedAt);
        }

        void deliverAsAnswerToARecovery(String status, long stamp) {
            deliver(
                    dispatcher,
                    withStatus(status, wall0 + stamp).replace("<odds_change ", "<odds_change request_id=\"77\" "),
                    elapsed);
        }

        private void deliver(SessionDispatcher to, String xml, long receivedAt) {
            var at = Instant.ofEpochMilli(wall0 + receivedAt);
            to.handle(new RawDelivery(
                    xml.getBytes(StandardCharsets.UTF_8),
                    xml.length(),
                    FakeFeed.routingKey(xml),
                    ++tag,
                    0,
                    at,
                    mono0 + receivedAt,
                    at.minusMillis(500)));
        }

        @Override
        public void close() {
            shared.close();
        }
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
                null,
                2 << 20,
                "hi.-.live.odds_change.-.od:match.198314.-",
                ++tag,
                0,
                Instant.ofEpochMilli(1),
                1,
                null));
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

    // ---- closing

    @Test
    void aDeliveryTakenOnceTheCloseBeganReachesNoCallbackAndIsNotAcknowledged() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        dispatcher.start();
        try {
            awaitWaitingForADelivery(dispatcher.thread());
            // the close begins as the dispatcher waits; the broker delivers before the connection closes
            dispatcher.stop();
            Queues.offer(
                    transport.queue(),
                    delivery(
                            Fixtures.read(ODDS_CHANGE).getBytes(StandardCharsets.UTF_8),
                            FakeFeed.routingKey(Fixtures.read(ODDS_CHANGE))));
            assertThat(dispatcher.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                    .as("ended")
                    .isTrue();
        } finally {
            dispatcher.close();
        }
        assertThat(listener.messages).as("callbacks").isEmpty();
        assertThat(ext.calls).as("raw callbacks").isEmpty();
        assertThat(transport.acked).as("acknowledged").isEmpty();
        assertThat(facts.facts).as("facts posted").isEmpty();
        assertThat(dispatcher.handled()).isZero();
    }

    @Test
    void aDeliveryPolledBeforeTheCloseBeganButCalledAfterItReachesNoListenerAndIsNotAcknowledged() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        // the close begins after the poll, in the last raw callback: the session's listener is next
        ext.afterBytes = dispatcher::stop;
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(ext.calls).as("raw callbacks").hasSize(2);
        assertThat(listener.messages).as("callbacks").isEmpty();
        assertThat(transport.acked).as("acknowledged").isEmpty();
        assertThat(facts.facts).as("facts posted").isEmpty();
        assertThat(dispatcher.handled()).isZero();
    }

    @Test
    void aDeliveryPolledBeforeTheCloseBeganButClosedInTheFirstRawCallbackReachesNoLaterCallbackAndIsNotAcknowledged() {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        // the close begins in the first raw callback: the bytes callback is next
        ext.afterReceived = dispatcher::stop;
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(ext.calls).as("raw callbacks").singleElement().asString().startsWith("onRawFeedMessageReceived");
        assertThat(listener.messages).as("callbacks").isEmpty();
        assertThat(transport.acked).as("acknowledged").isEmpty();
        assertThat(facts.facts).as("facts posted").isEmpty();
        assertThat(dispatcher.handled()).isZero();
    }

    @Test
    void aDeliveryHandledOnceTheFeedsCloseBeganButBeforeThisSessionWasStoppedReachesNothingAndIsNotAcknowledged() {
        for (var xml : List.of(
                Fixtures.read(ODDS_CHANGE), FeedMessages.alive(2, true), FeedMessages.snapshotComplete(2, 712))) {
            var dispatcher = dispatcher(MessageInterest.ALL);
            // the feed has marked itself closed; it has not got to this dispatcher yet
            ext.afterBytes = () -> feedClosed.set(true);
            handle(dispatcher, xml);
            assertThat(listener.messages).as("callbacks").isEmpty();
            assertThat(facts.facts).as("facts posted").isEmpty();
            assertThat(transport.acked).as("acknowledged").isEmpty();
            assertThat(dispatcher.handled()).isZero();
            feedClosed.set(false);
        }
    }

    @Test
    void aDeliveryWhoseMessageBuildFailsOnceTheCloseBeganPostsNoFactAndIsNotAcknowledged() throws InterruptedException {
        var dispatcher = dispatcher(MessageInterest.ALL);
        // the close begins before the build, which then fails, as one the close cuts short does: the
        // delivery returns early, without reaching a listener
        ext.afterBytes = dispatcher::stop;
        handle(dispatcher, Fixtures.read(BET_STOP), "hi.-.live.bet_stop.-.od:tournament.7.-");
        assertThat(failures.next().callback()).as("the build that failed").isEqualTo("build");
        assertThat(listener.messages).as("callbacks").isEmpty();
        assertThat(facts.facts).as("facts posted").isEmpty();
        assertThat(transport.acked).as("acknowledged").isEmpty();
        assertThat(dispatcher.handled()).isZero();
    }

    @Test
    void anAliveOrSnapshotCompletePolledBeforeTheCloseBeganButHandledAfterItPostsNoFactAndIsNotAcknowledged() {
        for (var message : List.of(FeedMessages.alive(2, true), FeedMessages.snapshotComplete(2, 712))) {
            var dispatcher = dispatcher(MessageInterest.ALL);
            // the close begins after the poll, in the last raw callback: the fact would be next
            ext.afterBytes = dispatcher::stop;
            handle(dispatcher, message);
            assertThat(ext.calls).as("raw callbacks").hasSize(2);
            assertThat(facts.facts).as("facts posted").isEmpty();
            assertThat(transport.acked).as("acknowledged").isEmpty();
            assertThat(dispatcher.handled()).isZero();
            ext.calls.clear();
        }
    }

    @Test
    void aCallbackThatClosesTheSessionStillEndsItsDeliveryWithItsFactAndAcknowledgement() {
        var dispatcher = dispatcher(MessageInterest.ALL);
        // the close begins inside the callback admitted for the delivery: what it began is finished
        listener.onOdds = message -> dispatcher.stop();
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(listener.messages).as("callbacks").hasSize(1);
        assertThat(facts.facts).as("facts posted").hasSize(1).first().asString().startsWith("processed");
        assertThat(transport.acked).as("acknowledged").hasSize(1);
        assertThat(dispatcher.handled()).isEqualTo(1);
    }

    @Test
    void aCallbackThatClosesTheFeedStillEndsItsDeliveryWithItsFactAndAcknowledgement() {
        var dispatcher = dispatcher(MessageInterest.ALL);
        // the feed's close signal is set inside the callback, before this session was stopped
        listener.onOdds = message -> feedClosed.set(true);
        handle(dispatcher, Fixtures.read(ODDS_CHANGE));
        assertThat(listener.messages).as("callbacks").hasSize(1);
        assertThat(facts.facts).as("facts posted").hasSize(1).first().asString().startsWith("processed");
        assertThat(transport.acked).as("acknowledged").hasSize(1);
        assertThat(dispatcher.handled()).isEqualTo(1);
    }

    @Test
    void anAliveOrSnapshotCompleteWhoseFactIsPostedAsTheCloseBeginsIsAcknowledgedWithIt() {
        for (var message : List.of(FeedMessages.alive(2, true), FeedMessages.snapshotComplete(2, 712))) {
            var dispatcher = dispatcher(MessageInterest.ALL);
            // the close begins after the check before the fact, and before the delivery's end: the
            // fact and the acknowledgement go together, as they do after a callback
            facts.beforePost = dispatcher::stop;
            handle(dispatcher, message);
            assertThat(facts.facts).as("facts posted").hasSize(1);
            assertThat(transport.acked).as("acknowledged").hasSize(1);
            assertThat(dispatcher.handled()).isEqualTo(1);
            facts.facts.clear();
            transport.acked.clear();
        }
    }

    @Test
    void anInterruptedCloseStillWaitsForTheCallbackAndKeepsTheInterrupt() throws InterruptedException {
        SessionDispatcher dispatcher = dispatcher(MessageInterest.ALL);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        listener.onOdds = message -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        dispatcher.start();
        var releaser = Thread.ofVirtual().unstarted(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        try {
            Queues.offer(
                    transport.queue(),
                    delivery(
                            Fixtures.read(ODDS_CHANGE).getBytes(StandardCharsets.UTF_8),
                            FakeFeed.routingKey(Fixtures.read(ODDS_CHANGE))));
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            releaser.start();
            dispatcher.stop();
            Thread.currentThread().interrupt();
            boolean ended;
            boolean interrupted;
            try {
                ended = dispatcher.awaitStop(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
            } finally {
                interrupted = Thread.interrupted();
            }
            assertThat(ended).as("ended, though the closer was interrupted").isTrue();
            assertThat(interrupted).as("the closer's interrupt kept").isTrue();
            assertThat(transport.acked)
                    .as("the message the callback had, acknowledged")
                    .hasSize(1);
            assertThat(facts.facts).as("and its fact posted").hasSize(1);
            assertThat(dispatcher.handled()).isEqualTo(1);
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }

    // ---- helpers

    /** Until the thread waits in its poll for the next delivery. */
    private static void awaitWaitingForADelivery(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(thread.getState())
                .as("the dispatcher waiting for a delivery")
                .isEqualTo(Thread.State.TIMED_WAITING);
    }

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
                clock);
        return new SessionDispatcher(
                1, session, interest, listener, ext, transport, facts, replay, feedClosed::get, pipeline);
    }

    private void handle(SessionDispatcher dispatcher, String xml) {
        handle(dispatcher, xml, FakeFeed.routingKey(xml));
    }

    private void handle(SessionDispatcher dispatcher, String xml, String routingKey) {
        dispatcher.handle(delivery(xml.getBytes(StandardCharsets.UTF_8), routingKey));
    }

    /** One delivery received by the split clock's reading now. */
    private void deliver(SessionDispatcher dispatcher, SplitClock split, String xml) {
        var received = Instant.ofEpochMilli(split.clock().wallMillis());
        dispatcher.handle(new RawDelivery(
                xml.getBytes(StandardCharsets.UTF_8),
                xml.length(),
                FakeFeed.routingKey(xml),
                ++tag,
                0,
                received,
                split.clock().monoMillis(),
                received.minusMillis(500)));
    }

    private RawDelivery delivery(byte[] body, String routingKey) {
        // received now: the live state takes a feed write as the feed's for the match status age from then
        Instant receivedAt = Instant.now();
        lastReceived = receivedAt;
        return new RawDelivery(
                body,
                body.length,
                routingKey,
                ++tag,
                0,
                receivedAt,
                receivedAt.toEpochMilli(),
                receivedAt.minusMillis(500));
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
        /** Runs after the last raw callback, before the session's listener is called. */
        volatile Runnable afterBytes = () -> {};
        /** Runs after the first raw callback, before the second. */
        volatile Runnable afterReceived = () -> {};

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
            afterReceived.run();
        }

        @Override
        public void onRawFeedMessageBytes(
                byte[] body, MessageInterest messageInterest, RoutingKeyInfo routingKey, MessageTimestamp timestamp) {
            created.add(timestamp.getCreated());
            String xml = new String(body, StandardCharsets.UTF_8);
            calls.add("onRawFeedMessageBytes " + xml.substring(1, xml.indexOf(' ')) + " " + messageInterest);
            throwIf("onRawFeedMessageBytes");
            afterBytes.run();
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
        final List<Stamp> taken = new CopyOnWriteArrayList<>();
        /** The stamp each fact's checkpoint is taken from, in the order of the facts. */
        final List<Long> marks = new CopyOnWriteArrayList<>();
        /** Runs as a fact is posted, before it is recorded. */
        volatile Runnable beforePost = () -> {};

        @Override
        public void processed(long producerId, long generatedAt, long markAt, Stamp taken, long requestId) {
            beforePost.run();
            marks.add(markAt);
            facts.add("processed " + producerId + " " + generatedAt + " request=" + requestId);
            this.taken.add(taken);
        }

        @Override
        public void alive(long producerId, long generatedAt, long markAt, Stamp taken, boolean subscribed) {
            beforePost.run();
            marks.add(markAt);
            facts.add("alive " + producerId + " " + generatedAt + " subscribed=" + subscribed);
            this.taken.add(taken);
        }

        @Override
        public void snapshotComplete(long producerId, long requestId) {
            beforePost.run();
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
