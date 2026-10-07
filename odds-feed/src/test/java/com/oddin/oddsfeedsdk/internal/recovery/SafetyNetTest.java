package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The stale-message safety net: when it acts, what it asks for first, when it is off or paused,
 * and its own cap. The limit is two minutes and the window one, as {@link Harness#settings()} has
 * them; the messages here are three minutes old.
 */
class SafetyNetTest {

    private static final Duration OLD = Duration.ofMinutes(3);
    private static final Duration FRESH = Duration.ofSeconds(1);

    /** How far behind the SDK's clock each producer's clock is, in millis. */
    private long preSkew;

    private long liveSkew;

    private boolean alives = true;
    /** Whether the live producer sends its alives when the prematch one does. */
    private boolean liveAlives = true;

    /** The request id of the messages {@link #stale} feeds: 0 for live ones. */
    private long requestId;

    @Test
    void liveMessagesTooOldForTheWindowGetRecoveriesFirstAndTheResetOnceAllAreAccepted() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        long seconds = stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(seconds)
                .as("seconds to the first request: the window after the first stale sample")
                .isEqualTo(61);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        assertThat(feed.calls)
                .as("one recovery for each producer of the session")
                .hasSize(before + 2);
        assertThat(pre.after()).isEqualTo(Instant.ofEpochMilli(feed.machine.checkpoint(1, PRE)));
        assertThat(feed.resets).as("before the API has accepted").isEmpty();

        feed.accept(pre);
        assertThat(feed.resets).as("with one of two accepted").isEmpty();
        feed.accept(live);
        assertThat(feed.resets).containsExactly(1);
        assertThat(feed.events).as("before the transport has made it").isEmpty();
        assertThat(feed.counters.resets()).isZero();
        assertThat(feed.lastStatus(PRE))
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.SAFETY_NET_RESET);
        assertThat(feed.lastStatus(LIVE))
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.SAFETY_NET_RESET);
        assertThat(feed.calls).as("nothing more while the transport resets").hasSize(before + 2);

        // what the first two sent before the reset may have gone with the old queue
        feed.resetDone();
        assertThat(feed.events).containsExactly("session 1 reset for producer 1");
        assertThat(feed.counters.resets()).isEqualTo(1);
        Outbox.Call.Snapshot preAgain = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot liveAgain = feed.lastSnapshot(LIVE);
        assertThat(feed.calls).as("asked for again once the reset is done").hasSize(before + 4);
        assertThat(preAgain.after()).isEqualTo(pre.after());
        assertThat(liveAgain.after()).isEqualTo(live.after());
        feed.complete(preAgain, 1);
        feed.complete(liveAgain, 1);
        assertThat(feed.lastStatus(PRE))
                .as("recovered, but its session still processes it late")
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aSessionsAliveWithoutAPositiveTimestampIsNoSampleAndMovesNothing() {
        for (long stamp : new long[] {0, -Duration.ofHours(1).toMillis()}) {
            Harness feed = Harness.upWith(MessageInterest.ALL);
            feed.clock.advance(Duration.ofSeconds(1));
            long processedAt = feed.now();
            feed.machine.processed(1, PRE, processedAt, feed.now(), 0);
            long checkpoint = feed.machine.checkpoint(1, PRE);
            int before = feed.calls.size();
            // the session takes nothing but such alives, past the window after the limit
            for (int second = 1; second <= Duration.ofMinutes(5).toSeconds(); second++) {
                feed.clock.advance(Duration.ofSeconds(1));
                if (second % 10 == 0) {
                    aliveBoth(feed);
                }
                feed.machine.sessionAlive(1, PRE, stamp, feed.now(), second % 2 == 0);
                feed.machine.tick();
            }
            assertThat(feed.calls)
                    .as("requests, with the session's alives stamped " + stamp)
                    .hasSize(before);
            assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(checkpoint);
            assertThat(requireNonNull(feed.producers.getProducer(PRE)).getLastProcessedMessageGenTimestamp())
                    .as("the last processed message's timestamp")
                    .isEqualTo(processedAt);
        }
    }

    @Test
    void aSnapshotCompleteWhileTheResetIsUnderWayIsIgnoredAndTheRecoveryAskedForAgain() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets).containsExactly(1);

        // the transport has not replaced the channel yet: these come from the old queue
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.machine.snapshotComplete(2, PRE, pre.requestId());
        feed.runWithAlives(Duration.ofSeconds(20));
        assertThat(feed.producers.isProducerDown(PRE))
                .as("while the reset is under way")
                .isTrue();
        assertThat(feed.calls).as("nothing asked for while it is").hasSize(before + 2);

        feed.resetDone();
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(pre.requestId());
        feed.complete(again, 1, 2);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        // a late report of the same reset changes nothing
        feed.resetDone();
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void theNetWaitsWhileAnotherProducerOfTheSessionHasARecoveryInFlight() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        feed.unsubscribed(LIVE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(live);
        int before = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("a request while the live producer recovers")
                .isEqualTo(-1);
        assertThat(feed.resets).isEmpty();

        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("seconds to the net's request once it is done: stale all along")
                .isEqualTo(1);
        assertThat(feed.calls).hasSize(before + 2);
    }

    @Test
    void aProducerWithItsCapSpentDoesNotHoldTheNetBack() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        feed.unsubscribed(LIVE);
        feed.refuse(feed.lastSnapshot(LIVE));
        int pre = feed.snapshots(PRE).size();
        // the live producer's recovery is refused each time it is asked for again, until its cap is spent
        long seconds = 0;
        while (feed.snapshots(PRE).size() == pre && seconds < 180) {
            stale(feed, 1, OLD, Duration.ofSeconds(1), () -> false);
            seconds++;
            Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
            if (feed.machine.inFlightRecovery(LIVE) == live.requestId()) {
                feed.refuse(live);
            }
        }
        assertThat(seconds).as("seconds to the net's request: the window").isEqualTo(61);
        assertThat(feed.counters.failed())
                .as("the live producer's cap spent by then")
                .isEqualTo(4);
        int live = feed.snapshots(LIVE).size();
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).as("the one producer it could ask for accepted").containsExactly(1);
        feed.resetDone();
        assertThat(feed.snapshots(LIVE))
                .as("the live producer, still capped, waits")
                .hasSize(live);
    }

    @Test
    void aSilentProducerDoesNotHoldTheNetBackAndIsAskedForOnceItSpeaksAgain() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        liveAlives = false;
        long seconds = stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(seconds)
                .as("seconds to the net's request, the live producer silent since 20 s")
                .isEqualTo(61);
        assertThat(feed.lastStatus(LIVE))
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.calls).as("the prematch producer only").hasSize(before + 1);
        long liveCheckpoint = feed.machine.checkpoint(1, LIVE);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);
        feed.resetDone();
        assertThat(feed.snapshots(PRE)).as("asked for again after the reset").hasSize(3);

        liveAlives = true;
        feed.alive(LIVE);
        assertThat(feed.lastSnapshot(LIVE).after())
                .as("the live producer misses what the reset dropped of it, and what it missed while silent")
                .isEqualTo(Instant.ofEpochMilli(liveCheckpoint));
    }

    @Test
    void aProducerIsNotUpWhileALowPrioritySessionIsBeingReset() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        // next to a high-priority session the low-priority one takes no snapshot completes
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets).containsExactly(2);

        // the high-priority session completes them while the low-priority one is still being reset
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        feed.runWithAlives(Duration.ofSeconds(20));
        assertThat(feed.producers.isProducerDown(PRE))
                .as("while the reset is under way")
                .isTrue();
        assertThat(feed.calls).as("nothing asked for into a queue about to go").hasSize(before + 2);

        feed.resetDone();
        assertThat(feed.calls).as("asked for again once the reset is done").hasSize(before + 4);
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void aResetTheTransportCouldNotTakeAsksAgainForWhatItIgnoredAndDropsNothing() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        feed.accept(pre);
        feed.accept(feed.lastSnapshot(LIVE));
        // ignored while the reset was meant to be under way
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.resetRefused();
        assertThat(feed.calls)
                .as("asked for again, nothing else waiting for the session")
                .hasSize(before + 4);
        assertThat(feed.lastSnapshot(PRE).after())
                .as("from where the net started")
                .isEqualTo(pre.after());
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        assertThat(feed.lastStatus(PRE))
                .as("recovered, but its session still processes it late")
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aSessionThatSeesTheSnapshotCompleteOfARecoveryItsResetAskedForIsNotReset() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        feed.accept(pre);
        // the lagging session has what the recovery sent; the other session has yet to see it
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).as("a reset now would drop what it just got").isEmpty();
    }

    @Test
    void aResetTheTransportCouldNotTakeCountsAgainstNeitherTheCapNorTheBackoff() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        long start = feed.now();
        for (int round = 1; round <= 4; round++) {
            int before = feed.calls.size();
            assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before))
                    .as("seconds to the net's request in round " + round)
                    .isEqualTo(61);
            feed.accept(feed.lastSnapshot(PRE));
            feed.accept(feed.lastSnapshot(LIVE));
            feed.resetRefused();
            feed.complete(feed.lastSnapshot(PRE), 1);
            feed.complete(feed.lastSnapshot(LIVE), 1);
        }
        assertThat(feed.machine.lagging(1))
                .as("four resets not made spend nothing")
                .isFalse();
        assertThat(feed.counters.resets()).isZero();
        assertThat(feed.now() - start).isEqualTo(Duration.ofSeconds(4 * 61).toMillis());
    }

    @Test
    void whatTheResetDropsOfAProducerItCouldNotAskForStartsWhereItWasWhenTheResetWasMade() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.start();
        feed.bothUp(1, 2);
        // the live producer's cap spent, for a gap of session 2 that starts far ahead
        feed.machine.processed(2, LIVE, feed.now() + Duration.ofHours(1).toMillis(), feed.now(), 0);
        feed.machine.channelLost(2);
        feed.machine.channelReopened(2);
        for (int attempt = 0; attempt < 4; attempt++) {
            feed.refuse(feed.lastSnapshot(LIVE));
            feed.runWithAlives(Duration.ofSeconds(20));
        }
        assertThat(feed.lastStatus(LIVE))
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.RECOVERY_FAILED);

        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("the prematch producer only").hasSize(before + 1);
        long atReset = feed.machine.checkpoint(1, LIVE);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);
        // the new channel delivers before the transport reports the reset done
        feed.machine.sessionAlive(1, LIVE, feed.now() + Duration.ofHours(2).toMillis(), feed.now(), true);
        feed.resetDone();

        feed.runWithAlives(Duration.ofMinutes(10));
        assertThat(feed.lastSnapshot(LIVE).after())
                .as("re-armed after the cool-down, from where session 1 was when the reset was made")
                .isEqualTo(Instant.ofEpochMilli(atReset));
    }

    @Test
    void aSessionNoOneTakesSnapshotCompletesForIsResetOnceTheApiAcceptedEveryRecovery() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        feed.close(1);
        int before = feed.calls.size();
        stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        // each completes on its acceptance, with nothing to wait for; that delivered nothing yet
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).isEmpty();
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).as("once both are accepted").containsExactly(2);
    }

    @Test
    void aLaggingSessionStopsLaggingOnceTheTransportHasMadeTheNextReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        while (!feed.machine.lagging(1)) {
            int before = feed.calls.size();
            stale(feed, 1, OLD, Duration.ofMinutes(10), () -> feed.calls.size() > before || feed.machine.lagging(1));
            if (feed.calls.size() > before) {
                feed.accept(feed.lastSnapshot(PRE));
                feed.accept(feed.lastSnapshot(LIVE));
                feed.resetDone();
                feed.complete(feed.lastSnapshot(PRE), 1);
                feed.complete(feed.lastSnapshot(LIVE), 1);
            }
        }
        // still behind, until the first reset ages out of the cool-down and the net acts again
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(10), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.machine.lagging(1))
                .as("until the transport has made the reset")
                .isTrue();
        feed.resetDone();
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.events.subList(feed.events.size() - 2, feed.events.size()))
                .containsExactly("session 1 reset for producer 1", "session 1 caught up");
    }

    @Test
    void aResetWithTheTransportWhenTheSessionsChannelGoesStillCountsWhenItIsDone() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);

        feed.machine.channelLost(1);
        feed.alive(PRE);
        feed.alive(LIVE);
        // the reset opens the new channel, and then reports itself done
        feed.machine.channelReopened(1);
        assertThat(feed.calls)
                .as("nothing asked for while the reset may still drop the new channel's queue")
                .hasSize(before + 2);
        feed.resetDone();
        assertThat(feed.calls).as("asked for once it is done").hasSize(before + 4);
        assertThat(feed.counters.resets()).isEqualTo(1);
    }

    @Test
    void anEventRecoveryAskedForWhileAResetIsUnderWayGoesOutOnceItIsDone()
            throws ExecutionException, InterruptedException {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);

        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        assertThat(feed.calls).as("its snapshot could go to either channel").hasSize(before + 2);
        feed.resetDone();
        Outbox.Call event = feed.calls.stream()
                .filter(call -> call instanceof Outbox.Call.Event)
                .findFirst()
                .orElseThrow();
        feed.accept(event);
        assertThat(reply.get()).isEqualTo(event.requestId());
        feed.machine.snapshotComplete(1, LIVE, event.requestId());
        assertThat(feed.events)
                .last()
                .isEqualTo("event recovery " + event.requestId() + " of " + Harness.MATCH + " completed");
    }

    @Test
    void anotherSessionsSnapshotCompleteDoesNotCancelALowPrioritySessionsReset() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        // the high-priority session completes one before the API has answered the other
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.accept(live);
        assertThat(feed.resets)
                .as("no evidence session 2's backlog was replaced")
                .containsExactly(2);
        feed.resetDone();
        assertThat(feed.calls)
                .as("the one complete already, asked for again at once")
                .hasSize(before + 3);
        assertThat(feed.lastSnapshot(PRE).requestId()).isNotEqualTo(pre.requestId());
        // the other was in flight, waiting for no snapshot complete of session 2: one more after it
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.calls).hasSize(before + 4);
        assertThat(feed.lastSnapshot(LIVE).requestId()).isNotEqualTo(live.requestId());
    }

    @Test
    void anotherSessionsSnapshotCompleteOfTheLastRequestLetsALowPrioritySessionsResetGoAhead() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        // the last one's snapshot complete, seen by the high-priority session before the API answers
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.resets)
                .as("the acceptance it proves lets the reset go ahead")
                .containsExactly(2);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("while session 2 is reset")
                .isTrue();
        feed.resetDone();
        assertThat(feed.calls).as("asked for again once it is done").hasSize(before + 3);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        assertThat(feed.lastStatus(LIVE)).extracting(ProducerStatusChange::down).isEqualTo(false);
        // the other, still in flight, asks for one more once it completes
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        assertThat(feed.calls).hasSize(before + 4);
    }

    @Test
    void deferredEventRecoveriesCountAgainstTheCap() throws ExecutionException, InterruptedException {
        Harness feed = resetUnderWay();
        int calls = feed.calls.size();
        for (int i = 0; i < 128; i++) {
            assertThat(feed.recoverEvent(LIVE)).isNotDone();
        }
        CompletableFuture<@Nullable Long> over = feed.recoverEvent(LIVE);
        assertThat(over).as("the 129th, refused").isDone();
        assertThat(over.get()).isNull();
        assertThat(feed.counters.eventRefused()).isEqualTo(1);
        assertThat(feed.calls).as("all of them wait for the reset").hasSize(calls);
    }

    @Test
    void closingAnswersADeferredEventRecoveryNull() throws ExecutionException, InterruptedException {
        Harness feed = resetUnderWay();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        feed.machine.close();
        assertThat(reply).isDone();
        assertThat(reply.get()).isNull();
    }

    @Test
    void aDeferredEventRecoveryGoesOutWhenTheResettingSessionCloses() {
        Harness feed = resetUnderWay();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        feed.close(1);
        assertThat(feed.calls).last().isInstanceOf(Outbox.Call.Event.class);
    }

    @Test
    void aDeferredEventRecoveryGoesOutWhenTheResetIsTurnedAway() {
        Harness feed = resetUnderWay();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        feed.resetRefused();
        assertThat(feed.calls)
                .filteredOn(call -> call instanceof Outbox.Call.Event)
                .hasSize(1);
    }

    /** Session 1 behind, the API's acceptance of both recoveries, and the reset with the transport. */
    private Harness resetUnderWay() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        return feed;
    }

    @Test
    void aDeferredEventRecoveryExpiresAsOneAskedForWould() throws ExecutionException, InterruptedException {
        Harness feed = resetUnderWay();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        feed.advance(Duration.ofHours(6));
        assertThat(reply).as("at the maximum recovery time").isNotDone();
        feed.advance(Duration.ofSeconds(1));
        assertThat(reply).isDone();
        assertThat(reply.get()).isNull();
        assertThat(feed.counters.eventExpired()).isEqualTo(1);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.counters.eventExpired()).as("counted once").isEqualTo(1);
        feed.resetDone();
        assertThat(feed.calls).as("nothing sent for it").noneMatch(call -> call instanceof Outbox.Call.Event);
    }

    @Test
    void expiredDeferredEventRecoveriesFreeTheirPlaces() {
        Harness feed = resetUnderWay();
        for (int i = 0; i < 128; i++) {
            assertThat(feed.recoverEvent(LIVE)).isNotDone();
        }
        feed.advance(Duration.ofHours(6).plusSeconds(1));
        assertThat(feed.counters.eventExpired()).isEqualTo(128);
        assertThat(feed.recoverEvent(LIVE)).as("a place again").isNotDone();
        assertThat(feed.counters.eventRefused()).isZero();
        feed.resetDone();
        assertThat(feed.calls)
                .filteredOn(call -> call instanceof Outbox.Call.Event)
                .hasSize(1);
    }

    @Test
    void aSnapshotCompleteOfARecoveryTheResetDidNotAskForLeavesItWaiting() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        liveAlives = false;
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("the prematch producer only").hasSize(before + 1);
        // the silent producer speaks while the reset waits for the API, and is recovered on its own
        liveAlives = true;
        feed.alive(LIVE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.complete(live, 1);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets)
                .as("a completion of a recovery it did not ask for")
                .containsExactly(1);
    }

    @Test
    void aReportOfNoResetUnderWayChangesNothing() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        // while it still waits for the API, a report with the number it will have
        feed.accept(pre);
        feed.machine.resetDone(1, 1, true);
        feed.machine.resetDone(1, 0, true);
        assertThat(feed.calls).as("before the reset is made").hasSize(before + 2);
        feed.accept(live);
        long number = feed.resetNumbers.getLast();
        feed.machine.resetDone(1, number + 1, true);
        feed.machine.resetDone(1, 0, true);
        assertThat(feed.calls).as("reports of other resets").hasSize(before + 2);
        feed.machine.resetDone(1, number, true);
        assertThat(feed.calls).as("its own report").hasSize(before + 4);
    }

    @Test
    void aResetWithTheTransportWhenTheConnectionGoesStillCountsWhenItIsDone() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);

        feed.machine.connectionDown();
        feed.machine.connectionUp();
        feed.alive(PRE);
        feed.alive(LIVE);
        assertThat(feed.calls)
                .as("nothing asked for while the old reset may still drop the new queue")
                .hasSize(before + 2);
        feed.resetDone();
        assertThat(feed.calls).as("asked for once it is done").hasSize(before + 4);
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        assertThat(feed.lastStatus(PRE))
                .as("recovered, but its session still processes it late")
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aRefusedRequestOfTheNetLeavesTheProducersRetriesWhole() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.refuse(feed.lastSnapshot(PRE));
        feed.refuse(feed.lastSnapshot(LIVE));
        // a real recovery after that is refused once: asked for again after the first pause
        feed.unsubscribed(PRE);
        feed.refuse(feed.lastSnapshot(PRE));
        int asked = feed.snapshots(PRE).size();
        feed.runWithAlives(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).as("after 5 s").hasSize(asked + 1);
        assertThat(feed.counters.reissued())
                .as("a first request, not a re-issue")
                .isEqualTo(1);
    }

    @Test
    void agesAreCorrectedByEachProducersOwnClockOffset() {
        preSkew = Duration.ofHours(1).toMillis();
        liveSkew = -Duration.ofMinutes(30).toMillis();
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        aliveBoth(feed);
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int before = feed.calls.size();

        // an hour old by the SDK's clock, a second by the producer's
        long seconds = stale(feed, 1, FRESH, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(seconds).as("a request").isEqualTo(-1);
        assertThat(feed.events).isEmpty();
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("three minutes old by the producer's clock").hasSize(before + 2);
    }

    @Test
    void theNetIsOffForAProducerWhoseLastAliveIsOlderThanTwoIntervals() {
        Harness feed = new Harness(Harness.settings(Duration.ofMinutes(5)));
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        alives = false;
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("a request without alives")
                .isEqualTo(-1);
        alives = true;
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("seconds to a request once alives resume: the first at 10 s, then the window")
                .isEqualTo(70);
    }

    @Test
    void snapshotMessagesAreNoSamples() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        long checkpoint = feed.machine.checkpoint(1, PRE);
        requestId = feed.lastSnapshot(PRE).requestId();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .isEqualTo(-1);
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(checkpoint);
    }

    @Test
    void theNetIsPausedOnEverySessionWhileARecoveryOfTheProducerIsInFlight() {
        Harness feed = new Harness();
        Outbox.Call.Snapshot recovery = recoveringWithTwoSessions(feed);
        int before = feed.calls.size();
        // the recovery's snapshot is ahead of session 2's live messages, which come out old
        assertThat(stale(feed, 2, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .isEqualTo(-1);

        feed.complete(recovery, 1, 2);
        long seconds = stale(feed, 2, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(seconds)
                .as("seconds from the completion to the net's request: the window starts again")
                .isEqualTo(61);
    }

    @Test
    void aSampleTakenBeforeTheCompletionButHandledAfterItIsStillTheRecoverys() {
        Harness feed = new Harness();
        Outbox.Call.Snapshot recovery = recoveringWithTwoSessions(feed);
        int before = feed.calls.size();
        feed.complete(recovery, 1, 2);
        long completedAt = feed.now();
        feed.machine.processed(2, PRE, completedAt - OLD.toMillis() - 1_000, completedAt - 1_000, 0);
        long seconds = stale(feed, 2, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(seconds)
                .as("seconds from the completion to the net's request")
                .isEqualTo(61);
    }

    /** Sessions 1 and 2, both producers up, and a recovery of producer 1 in flight, accepted. */
    private static Outbox.Call.Snapshot recoveringWithTwoSessions(Harness feed) {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        return recovery;
    }

    @Test
    void aRequestTheApiDidNotAcceptMeansNoResetAndTheNetBacksOff() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(live);
        feed.refuse(pre);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        assertThat(feed.statuses)
                .as("the net took nothing down")
                .noneMatch(change -> change.cause() == StatusCause.SAFETY_NET_RESET);
        feed.runWithAlives(Duration.ofSeconds(10));
        assertThat(feed.snapshots(PRE))
                .as("nothing missing, so nothing to ask for again")
                .hasSize(2);

        feed.complete(live, 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the next try: the window again, past the minute's backoff")
                .isEqualTo(61);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(after + 2);
    }

    @Test
    void theNetResetsASessionThreeTimesPerCooldownWithBackoffThenTheSessionLags() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        long start = feed.now();
        List<Long> resetAt = new ArrayList<>();
        while (feed.now() - start < Duration.ofMinutes(10).toMillis()) {
            int before = feed.calls.size();
            boolean lagging = stale(
                                    feed,
                                    1,
                                    OLD,
                                    Duration.ofMinutes(10),
                                    () -> feed.calls.size() > before || feed.machine.lagging(1))
                            > 0
                    && feed.machine.lagging(1);
            if (lagging) {
                break;
            }
            Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
            Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
            feed.accept(pre);
            feed.accept(live);
            resetAt.add((feed.now() - start) / 1_000);
            feed.resetDone();
            feed.complete(feed.lastSnapshot(PRE), 1);
            feed.complete(feed.lastSnapshot(LIVE), 1);
        }
        assertThat(resetAt)
                .as("seconds of the resets: the window, then 1 and 2 minutes apart")
                .containsExactly(61L, 122L, 242L);
        assertThat(feed.machine.lagging(1)).isTrue();
        assertThat(feed.events).last().isEqualTo("session 1 lagging");
        assertThat(feed.statuses)
                .filteredOn(change -> change.cause() == StatusCause.SAFETY_NET_RESET)
                .as("producers the net took down, once per reset each")
                .hasSize(6);

        // caught up
        stale(feed, 1, FRESH, Duration.ofSeconds(1), () -> false);
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.events).last().isEqualTo("session 1 caught up");

        // the first reset ages out of the cool-down: the net may reset again
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(10), () -> feed.calls.size() > before);
        assertThat((feed.now() - start) / 1_000).as("seconds to the next try").isEqualTo(661);
    }

    @Test
    void aSnapshotCompleteBeforeTheApisAnswerCancelsTheReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets)
                .as("a reset now would drop what the recovery sent")
                .isEmpty();

        // the reset given up, the net may act again once the other recovery is done
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: still stale, so as its backoff of a minute ends")
                .isEqualTo(60);
    }

    @Test
    void aSnapshotCompleteBeforeTheApisAnswerLetsTheNetActAgainOnASessionOfOneProducer() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.accept(pre);
        assertThat(feed.resets).isEmpty();
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the net's next request, the reset it waited for given up")
                .isEqualTo(60);
    }

    @Test
    void aResetTheTransportCouldNotTakeAfterOneItMadeWaitsNoLongerThanTheOneItMade() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        feed.resetDone();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);

        int second = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > second))
                .isEqualTo(61);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        feed.resetRefused();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);

        int third = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > third))
                .as("seconds to the next try: the window, past the minute the reset made set, not two")
                .isEqualTo(61);
        assertThat(feed.counters.resets()).isEqualTo(1);
    }

    @Test
    void aRecoveryWhoseSessionTheTransportStillResetsDoesNotTimeOutAndIsAskedForAgainOnceItIsDone() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);

        // the new channel takes the transport longer than the deadline for a snapshot complete
        feed.runWithAlives(Duration.ofMinutes(6));
        assertThat(feed.counters.timedOut()).isZero();
        assertThat(feed.calls).hasSize(before + 2);
        feed.resetDone();
        assertThat(feed.calls).as("asked for again once it is done").hasSize(before + 4);
        assertThat(feed.counters.failed()).isZero();
    }

    @Test
    void aLostConnectionWhileAResetWaitsMakesNone() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.machine.connectionDown();
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).isEmpty();
    }

    /**
     * Second by second, session {@code session} takes a live message of producer 1 that is {@code
     * age} old by that producer's clock, with both producers' alives every ten seconds of the
     * clock, until {@code until} holds.
     *
     * @return the seconds that took, or -1 when it did not hold within {@code limit}
     */
    private long stale(Harness feed, int session, Duration age, Duration limit, BooleanSupplier until) {
        for (long second = 1; second <= limit.toSeconds(); second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (alives && Duration.between(Harness.START, feed.clock.instant()).toSeconds() % 10 == 0) {
                aliveBoth(feed);
            }
            long now = feed.now();
            feed.machine.processed(session, PRE, now - preSkew - age.toMillis(), now, requestId);
            feed.machine.tick();
            if (until.getAsBoolean()) {
                return second;
            }
        }
        return -1;
    }

    private void aliveBoth(Harness feed) {
        long now = feed.now();
        feed.machine.alive(PRE, now - preSkew, now, true);
        if (liveAlives) {
            feed.machine.alive(LIVE, now - liveSkew, now, true);
        }
    }
}
