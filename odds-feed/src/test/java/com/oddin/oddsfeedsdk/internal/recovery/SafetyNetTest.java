package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.clock.Stamp;
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
            feed.machine.processed(1, PRE, processedAt, Stamp.of(feed.now()), 0);
            long checkpoint = feed.machine.checkpoint(1, PRE);
            int before = feed.calls.size();
            // the session takes nothing but such alives, past the window after the limit
            for (int second = 1; second <= Duration.ofMinutes(5).toSeconds(); second++) {
                feed.clock.advance(Duration.ofSeconds(1));
                if (second % 10 == 0) {
                    aliveBoth(feed);
                }
                feed.machine.sessionAlive(1, PRE, stamp, Stamp.of(feed.now()), second % 2 == 0);
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
    void aProducerWithItsCapSpentHoldsTheNetBackUntilItsCooldownIsOver() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        feed.unsubscribed(LIVE);
        feed.refuse(feed.lastSnapshot(LIVE));
        int pre = feed.snapshots(PRE).size();
        // the live producer's recovery is refused each time it is asked for again, until its cap is spent
        for (int second = 0; second < 120; second++) {
            stale(feed, 1, OLD, Duration.ofSeconds(1), () -> false);
            Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
            if (feed.machine.inFlightRecovery(LIVE) == live.requestId()) {
                feed.refuse(live);
            }
        }
        assertThat(feed.counters.failed()).as("the live producer's cap spent").isEqualTo(4);
        assertThat(feed.snapshots(PRE))
                .as("the session has been stale past the window, and the net asked for nothing")
                .hasSize(pre);
        assertThat(feed.events).as("it waits, with no lagging").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.resets).isEmpty();

        // the cool-down over, live is asked for on its own account at an alive: its gap comes first
        int live = feed.snapshots(LIVE).size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(11), () -> feed.machine.inFlightRecovery(LIVE) != 0))
                .isPositive();
        assertThat(feed.snapshots(LIVE)).hasSize(live + 1);
        assertThat(feed.snapshots(PRE)).as("the net still waits").hasSize(pre);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int before = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("seconds to the net's request once no producer holds it back: stale all along, no backoff")
                .isEqualTo(1);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(before + 2);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        feed.resetDone();
        assertThat(feed.calls)
                .as("both asked for again in the step of the reset")
                .hasSize(before + 4);
    }

    @Test
    void aSilentProducerHoldsTheNetBackUntilItSpeaksAgainAndItsRecoveryIsDone() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        liveAlives = false;
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("seconds to a request: none, with the live producer silent since 20 s")
                .isEqualTo(-1);
        assertThat(feed.lastStatus(LIVE))
                .extracting(ProducerStatusChange::cause)
                .isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.events).as("it waits, with no lagging").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();

        // it speaks, and is recovered on its own account first
        liveAlives = true;
        feed.alive(LIVE);
        assertThat(feed.calls).as("only the live producer's own recovery").hasSize(before + 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > after))
                .as("seconds to the net's request: stale all along")
                .isEqualTo(1);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(after + 2);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        feed.resetDone();
        assertThat(feed.calls)
                .as("both asked for again in the step of the reset")
                .hasSize(after + 4);
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
    void aProducerCappedForAnotherSessionsGapHoldsTheNetBack() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.start();
        feed.bothUp(1, 2);
        // the live producer's cap spent, for a gap of session 2 that starts far ahead
        feed.machine.processed(2, LIVE, feed.now() + Duration.ofHours(1).toMillis(), Stamp.of(feed.now()), 0);
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
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before))
                .as("seconds to a request: none, a producer session 1 receives is capped")
                .isEqualTo(-1);
        assertThat(feed.events).as("it waits, with no lagging").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.resets).isEmpty();
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
    void aRecoveryTheResetAskedForThatCompletesBeforeTheResetCancelsItWhoseSessionDidNotWaitForIt() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        // the high-priority session completes one before the API has answered the other: the recovery
        // is over, and what the reset would drop of it is not repeated by a recovery that is in flight
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        assertThat(feed.events).as("a recovery that completed is no failure").isEmpty();
        feed.accept(live);
        assertThat(feed.resets).as("the reset was cancelled when it ended").isEmpty();
        assertThat(feed.events).isEmpty();

        // the net tries again once the other recovery is done, with its backoff and window
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        int after = feed.calls.size();
        assertThat(stale(feed, 2, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: the window starts again when the recovery ends")
                .isEqualTo(61);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(after + 2);
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
        // the last one's snapshot complete, seen by the high-priority session before the API answers: it
        // says the API took it, and the reset is made in that step, before the recovery ends
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.resets)
                .as("the acceptance it proves lets the reset go ahead")
                .containsExactly(2);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("while session 2 is reset")
                .isTrue();
        feed.resetDone();
        assertThat(feed.calls)
                .as(
                        "both asked for again in the step the reset is done: the one that ended, and the one still in flight")
                .hasSize(before + 4);
        assertThat(feed.lastSnapshot(PRE).requestId()).isNotEqualTo(pre.requestId());
        assertThat(feed.lastSnapshot(LIVE).requestId()).isNotEqualTo(live.requestId());
        feed.complete(feed.lastSnapshot(LIVE), 1);
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(feed.lastStatus(LIVE)).extracting(ProducerStatusChange::down).isEqualTo(false);
        assertThat(feed.calls)
                .as("nothing more: the old recovery of prematch was given up")
                .hasSize(before + 4);
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
    void aProducerEnabledSinceTheNetAskedForItsRecoveriesCancelsTheResetAtTheLastAcceptance() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        feed.producers.setProducerState(LIVE, false);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        assertThat(feed.calls)
                .as("the prematch producer only: live is disabled")
                .hasSize(before + 1);

        // enabled again while the reset waits for the API: its backlog would go with the reset, unasked
        feed.producers.setProducerState(LIVE, true);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.failureReasons).as("the check that cancelled it").containsExactly("producer enabled since");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
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
                .as("seconds to a request once alives resume: the first at 10 s, a sample taken then is not the"
                        + " net's, and the window follows")
                .isEqualTo(71);
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
        feed.machine.processed(2, PRE, completedAt - OLD.toMillis() - 1_000, Stamp.of(completedAt - 1_000), 0);
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
    void aProducerDisabledBetweenTheNetsSelectionAndItsRequestCancelsTheResetAndTheNetActsAgain() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        // the client disables live while the net's request for prematch goes out: live's turn comes after
        feed.onRequest = call -> {
            if (call.producerId() == PRE) {
                feed.producers.setProducerState(LIVE, false);
            }
        };
        int before = feed.calls.size();
        staleBeforeTheTick(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.onRequest = call -> {};

        // at once, by the message that made the request: no tick has run since
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        feed.machine.tick();
        assertThat(feed.events).as("none more from the tick").hasSize(1);
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        assertThat(feed.snapshots(LIVE)).as("live was never asked").hasSize(1);
        assertThat(feed.resets).isEmpty();

        // the recovery that did go out ends, which pauses the net until then
        feed.complete(feed.lastSnapshot(PRE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: the net is not stuck on the disabled producer")
                .isPositive();
        assertThat(feed.calls).as("for the one that is enabled").hasSize(after + 1);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);
    }

    @Test
    void aProducerDisabledWhileTheResetWaitsForItsAcceptanceCancelsTheResetOnTheNextTick() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        feed.producers.setProducerState(LIVE, false);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        feed.accept(live);
        assertThat(feed.resets).as("the late answer finds no reset to make").isEmpty();

        // enabled again, the net works on the session as before
        feed.producers.setProducerState(LIVE, true);
        feed.complete(pre, 1);
        feed.complete(live, 1);
        int after = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after);
        assertThat(feed.calls).as("a recovery for each producer again").hasSize(after + 2);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
    }

    @Test
    void aResetWaitingOnlyForEnabledProducersIsLeftAloneByTheTicks() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        feed.advance(Duration.ofSeconds(1));
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.events).isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();
        feed.accept(live);
        assertThat(feed.resets).containsExactly(1);
    }

    @Test
    void aProducerDisabledWhileItsRecoveryIsAcceptedMakesNoResetEvenBeforeATick() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        // the last one the reset waits for is disabled, and its answer comes before any tick
        feed.producers.setProducerState(LIVE, false);
        feed.accept(live);
        assertThat(feed.resets).as("no reset for a producer that is disabled").isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        feed.machine.tick();
        assertThat(feed.events).as("none more from the tick").hasSize(1);
    }

    @Test
    void aSessionWhoseCheckpointLiesBeforeTheRecoveryWindowIsNotResetButLags() {
        // a one minute window: the checkpoint is the recoveries' end at the start, and the net acts a minute on
        Harness feed = new Harness(Harness.settings(), false, 1);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> false);
        assertThat(feed.calls).as("nothing is asked for").hasSize(before);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).as("lagging, told once").containsExactly("session 1 lagging");
        assertThat(feed.machine.lagging(1)).isTrue();
        assertThat(feed.statuses)
                .as("no gap opened, so nothing taken down")
                .noneMatch(change -> change.cause() == StatusCause.SAFETY_NET_RESET);

        // it stops lagging the usual way: messages that are on time
        stale(feed, 1, FRESH, Duration.ofSeconds(5), () -> false);
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.events).containsExactly("session 1 lagging", "session 1 caught up");
    }

    @Test
    void aProducerTheNetCannotAskForNowKeepsTheSessionWaitingEvenWhenItsCheckpointLiesBeforeItsWindow() {
        Harness feed = new Harness(Harness.settings(), false, 1);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        // live is silent, so it cannot be asked for, and its checkpoint stays where its recovery put it;
        // prematch is stale, but its checkpoint moves with the messages
        liveAlives = false;
        takeStaleWithCheckpoints(feed, 120);
        assertThat(feed.calls).as("nothing is asked for").hasSize(before);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events)
                .as("the window check comes after: no lagging yet")
                .isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    @Test
    void aCheckpointOfTheOtherProducerThatFellOutOfItsWindowWhileTheRecoveriesWereAcceptedCancelsTheReset() {
        Harness feed = new Harness(Harness.settings(), false, 4);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        takeStaleWithCheckpoints(feed, 61);
        assertThat(feed.calls).as("both asked for").hasSize(before + 2);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);

        // nothing is taken meanwhile: prematch's checkpoint, moved by its messages, stays inside the window;
        // live's, where its recovery left it, falls out of it, and live is producer 2
        for (int second = 1; second <= 70; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                aliveBoth(feed);
            }
            feed.machine.tick();
        }
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets)
                .as("a reset would drop live's backlog, which no recovery repeats")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.failureReasons).as("the check that cancelled it").containsExactly("beyond the recovery window");
    }

    @Test
    void aSessionWhoseCheckpointIsInsideTheRecoveryWindowIsResetAsBefore() {
        // a four minute window, a margin of two: the checkpoint, a minute back, is inside both
        Harness feed = new Harness(Harness.settings(), false, 4);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    @Test
    void aCheckpointThatFellOutOfTheRecoveryWindowWhileTheRecoveriesWereAcceptedCancelsTheReset() {
        // a four minute window, a margin of two: the checkpoint, a minute back, is clear of both when the net asks
        Harness feed = new Harness(Harness.settings(), false, 4);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        // the session takes nothing meanwhile, and the window moves on past its checkpoint
        for (int second = 1; second <= 70; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            feed.machine.tick();
        }
        feed.accept(live);
        assertThat(feed.resets)
                .as("the recoveries no longer repeat what the reset would drop")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.failureReasons).as("the check that cancelled it").containsExactly("beyond the recovery window");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
    }

    @Test
    void anAcceptanceHandledAfterTheFeedBeganToCloseMakesNoReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        feed.machine.closing();
        feed.accept(live);
        assertThat(feed.resets)
                .as("a reset at shutdown drops what the shutdown settles")
                .isEmpty();
    }

    @Test
    void aStaleWindowThatBeganBeforeARecoveryWithNoSampleOfTheLaneDoesNotCountItsTime() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofSeconds(30), () -> feed.calls.size() > before);

        // a recovery of the producer starts and completes, and the session takes no message of it meanwhile
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        int asked = feed.calls.size();
        for (int second = 1; second <= 40; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            feed.machine.tick();
        }
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.calls).as("nothing more asked for").hasSize(asked);

        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > asked))
                .as("seconds from the recovery's end to the net's request: a window of its own")
                .isEqualTo(61);
    }

    @Test
    void aStaleWindowOfALaneWhoseGapTheNetOpenedAndCancelledWithoutARecoveryDoesNotCarryOver() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        // the client disables live as the net's request for prematch goes out: live gets a gap and no request
        feed.onRequest = call -> {
            if (call.producerId() == PRE) {
                feed.producers.setProducerState(LIVE, false);
            }
        };
        int before = feed.calls.size();
        // live's messages are old for the first half minute only; the net acts on prematch's
        for (int second = 1; second <= 61; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            long now = feed.now();
            if (second <= 30) {
                feed.machine.processed(1, LIVE, now - OLD.toMillis(), Stamp.of(now), 0);
            }
            feed.machine.processed(1, PRE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.tick();
        }
        feed.onRequest = call -> {};
        assertThat(feed.calls).as("prematch asked for, live not").hasSize(before + 1);
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        feed.producers.setProducerState(LIVE, true);
        feed.complete(feed.lastSnapshot(PRE), 1);

        // past the backoff, with no message of live, a stale one of live starts a window of its own
        int asked = feed.calls.size();
        for (int second = 1; second <= 70; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            feed.machine.tick();
        }
        long now = feed.now();
        feed.machine.processed(1, LIVE, now - OLD.toMillis(), Stamp.of(now), 0);
        assertThat(feed.calls).as("not at once").hasSize(asked);
    }

    // ---- the backoff counts the resets within the cool-down only

    /**
     * Session 1 reset twice, the second a little over two minutes after the first, and then a third
     * time asked for, accepted, just before the first ages out of the ten minutes' cool-down.
     *
     * @return the time the first reset was made
     */
    private long twoResetsThenAThirdThatOutlastsTheFirst(Harness feed, boolean accept) {
        long first = 0;
        for (int round = 0; round < 2; round++) {
            int before = feed.calls.size();
            stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
            feed.accept(feed.lastSnapshot(PRE));
            feed.accept(feed.lastSnapshot(LIVE));
            feed.resetDone();
            if (round == 0) {
                first = feed.now();
            }
            feed.complete(feed.lastSnapshot(PRE), 1);
            feed.complete(feed.lastSnapshot(LIVE), 1);
        }
        // quiet, with messages on time, until the third is asked for a little before the first ages out
        while (feed.now() - first < Duration.ofSeconds(520).toMillis()) {
            stale(feed, 1, FRESH, Duration.ofSeconds(10), () -> false);
        }
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.now() - first)
                .as("the third asked for before the first ages out")
                .isLessThan(600_000);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        if (accept) {
            feed.accept(pre);
            feed.accept(live);
            assertThat(feed.resets).hasSize(3);
        }
        // the transport is slow: the first ages out of the cool-down before the third is done
        feed.runWithAlives(Duration.ofSeconds(601 - (feed.now() - first) / 1_000 + 1));
        assertThat(feed.now() - first).isGreaterThan(600_000);
        return first;
    }

    @Test
    void theBackoffAfterAResetCountsOnlyTheResetsWithinTheCooldown() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        twoResetsThenAThirdThatOutlastsTheFirst(feed, true);
        feed.resetDone();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(6), () -> feed.calls.size() > after))
                .as("seconds to the next request: two resets within the cool-down, so two minutes")
                .isEqualTo(120);
    }

    @Test
    void theBackoffAfterAResetTheTransportCouldNotTakeCountsOnlyTheResetsWithinTheCooldown() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        twoResetsThenAThirdThatOutlastsTheFirst(feed, true);
        feed.resetRefused();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(6), () -> feed.calls.size() > after))
                .as(
                        "seconds to the next request: one reset within the cool-down, so a minute's backoff, then the window")
                .isEqualTo(61);
    }

    @Test
    void theBackoffAfterACancelledResetCountsOnlyTheResetsWithinTheCooldown() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        twoResetsThenAThirdThatOutlastsTheFirst(feed, false);
        feed.refuse(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        feed.runWithAlives(Duration.ofSeconds(10));
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(6), () -> feed.calls.size() > after))
                .as("seconds to the next request, counted from ten seconds after the cancel: a pause of two minutes")
                .isEqualTo(110);
    }

    // ---- another session's queue that is not bound

    @Test
    void theNetDoesNotResetWhileAnotherSessionsLostChannelHoldsBackAProducerOfThisOne() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.start();
        feed.bothUp(1, 2);
        // session 2 has no queue, so live cannot be asked for, and a reset would drop session 1's live backlog
        feed.machine.channelLost(2);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("nothing is asked for").hasSize(before);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).as("no lagging either: it waits").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();

        // session 2's queue is back, live is recovered, and the net acts on session 1
        feed.machine.channelReopened(2);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.complete(live, 1, 2);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > after))
                .as("seconds to the net's request once no queue is missing: stale all along")
                .isEqualTo(1);
    }

    @Test
    void anotherSessionsChannelLostWhileTheResetWaitsCancelsItWithoutABackoffEvenWhenItWaitedForNothing() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        // next to a high-priority session the low-priority one takes no snapshot completes, so its
        // channel's loss gives up none of session 1's recoveries
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("both asked for").hasSize(before + 2);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);

        // session 2 waited for nothing, so nothing is given up: the loss itself cancels the reset of session 1,
        // whose producers it receives, with no failure to report
        feed.machine.channelLost(2);
        assertThat(feed.events).as("a loss is no failed request").isEmpty();
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets)
                .as("session 2 has no queue, so what a reset drops of it could not be asked for after")
                .isEmpty();
        assertThat(feed.events).isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();

        // its queue back, everything recovered: the net acts after the window, with no backoff on top
        feed.machine.channelReopened(2);
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: the window again, no ten minutes' backoff")
                .isEqualTo(61);
    }

    // ---- the net starts only when every producer can be asked, and ends with any recovery it asked

    @Test
    void aProducerWithAGapWhoseRetryIsDueAtTheStaleSampleIsRetriedFirstAndTheNetWaits() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofSeconds(59), () -> false);
        // the live producer's recovery is refused just before the window is over: it is in its backoff
        feed.unsubscribed(LIVE);
        feed.refuse(feed.lastSnapshot(LIVE));
        int live = feed.snapshots(LIVE).size();
        feed.advance(Duration.ofSeconds(1));
        assertThat(stale(
                        feed,
                        1,
                        OLD,
                        Duration.ofSeconds(3),
                        () -> feed.snapshots(PRE).size() > 1))
                .as("the net's request while live waits out its backoff")
                .isEqualTo(-1);
        assertThat(feed.snapshots(LIVE)).as("live is still waiting").hasSize(live);
        assertThat(feed.events).as("no lagging, no failed request").isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();

        // its backoff over, live's gap is still open: its own retry goes out, the net's request does not
        stale(feed, 1, OLD, Duration.ofSeconds(1), () -> false);
        assertThat(feed.snapshots(LIVE)).as("only the ordinary retry").hasSize(live + 1);
        assertThat(feed.snapshots(PRE))
                .as("the net did not ask at the very sample the retry came due")
                .hasSize(1);
        assertThat(feed.events).isEmpty();
        Outbox.Call.Snapshot retry = feed.lastSnapshot(LIVE);
        feed.accept(retry);
        assertThat(feed.resets).as("no pending reset: nothing to make").isEmpty();
        feed.machine.snapshotComplete(1, LIVE, retry.requestId());

        // the recovery done, nothing holds the net back, and it has started no backoff of its own
        assertThat(stale(
                        feed,
                        1,
                        OLD,
                        Duration.ofMinutes(1),
                        () -> feed.snapshots(PRE).size() > 1))
                .as("seconds to the net's request once live's recovery is done")
                .isEqualTo(1);
        assertThat(feed.calls).as("both producers asked for").hasSize(before + 2 + 2);
    }

    @Test
    void anotherSessionsResetUnderWayHoldsTheNetBackWithoutALaggingOrABackoff() {
        Harness feed = new Harness();
        feed.open(1, MessageInterest.ALL);
        // takes no snapshot completes, so session 1 alone completes the recovery its reset asked for
        feed.open(new SessionInfo(2, MessageInterest.LIVE_ONLY, false));
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        staleOf(feed, 2, LIVE, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(live);
        assertThat(feed.resets).containsExactly(2);
        // the recovery is done while session 2 is still being reset: only the reset holds live back
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.machine.inFlightRecovery(LIVE)).isZero();

        // session 1's prematch messages are stale: the net waits, with live not askable for the reset
        int calls = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > calls))
                .as("seconds to a request while session 2 is being reset")
                .isEqualTo(-1);
        assertThat(feed.events).as("it waits, with no lagging").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();

        // the reset done, live asked for again for session 2; once that is done, the net acts at once
        feed.resetDone();
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > after))
                .as("seconds to the net's request: stale all along, with no backoff of its own")
                .isEqualTo(1);
        assertThat(feed.calls).as("both producers asked for").hasSize(after + 2);
    }

    @Test
    void aRecoveryThatEndsAfterItsAcceptanceBeforeTheOthersCancelsTheReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);

        // the producer says it is unsubscribed since it took the request: the recovery is given up
        feed.unsubscribed(PRE);
        assertThat(feed.machine.inFlightRecovery(PRE)).isZero();
        assertThat(feed.events)
                .as("cancelled when it ended, with no failure to report")
                .isEmpty();
        feed.accept(live);
        assertThat(feed.resets)
                .as("the reset is not made on the last acceptance")
                .isEmpty();
        assertThat(feed.events).as("it was cancelled before, not now").isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();
    }

    @Test
    void aRecoveryOfTheNetThatTimesOutBeforeTheResetCancelsItAndTheNetBacksOff() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        // nothing of either is heard: the snapshot complete is waited for no longer than five minutes
        feed.runWithAlives(Duration.ofMinutes(6));
        assertThat(feed.counters.timedOut()).isEqualTo(2);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).as("one reset, one report").containsExactly("session 1 not reset for producer 1");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
    }

    @Test
    void aLostConnectionCancelsTheResetAndStartsNoBackoffOfTheNets() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.machine.connectionDown();
        assertThat(feed.events).as("a loss is no failure of the net's request").isEmpty();
        feed.machine.connectionUp();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: the window again, no ten minutes' backoff")
                .isEqualTo(61);
    }

    @Test
    void aLostChannelOfASessionThatWaitedForTheRecoveriesCancelsTheResetAndStartsNoBackoffOfTheNets() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));

        // session 2 has not seen their snapshot completes: they went with its queue, and the reset is given up
        feed.machine.channelLost(2);
        feed.machine.channelReopened(2);
        assertThat(feed.events).as("a loss is no failure of the net's request").isEmpty();
        feed.complete(feed.lastSnapshot(PRE), 1, 2);
        feed.complete(feed.lastSnapshot(LIVE), 1, 2);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: the window again, no ten minutes' backoff")
                .isEqualTo(61);
    }

    @Test
    void everyProducerIsMarkedDownBeforeTheOutboxIsToldToResetTheChannel() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        var downWhenTold = new ArrayList<Boolean>();
        feed.onReset = () -> {
            downWhenTold.add(feed.producers.isProducerDown(PRE));
            downWhenTold.add(feed.producers.isProducerDown(LIVE));
            downWhenTold.add(requireNonNull(feed.lastStatus(PRE)).cause() == StatusCause.SAFETY_NET_RESET);
            downWhenTold.add(requireNonNull(feed.lastStatus(LIVE)).cause() == StatusCause.SAFETY_NET_RESET);
        };
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        assertThat(downWhenTold)
                .as("down for the reset, both, when the outbox heard of it")
                .containsOnly(true)
                .hasSize(4);
    }

    @Test
    void aLowPrioritySessionsFollowUpsGoOutInTheStepTheResetIsDoneWhileTheHighPrioritySessionHasNotCompletedThem() {
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
        feed.accept(live);
        assertThat(feed.resets).containsExactly(2);

        // session 1 has completed neither, and session 2 never waits for them: both stay in flight until given up
        assertThat(feed.machine.inFlightRecovery(PRE)).isEqualTo(pre.requestId());
        assertThat(feed.machine.inFlightRecovery(LIVE)).isEqualTo(live.requestId());
        feed.resetDone();
        assertThat(feed.calls).as("both asked for again in this step").hasSize(before + 4);
        assertThat(feed.machine.inFlightRecovery(PRE))
                .isEqualTo(feed.lastSnapshot(PRE).requestId())
                .isNotEqualTo(pre.requestId());
        assertThat(feed.machine.inFlightRecovery(LIVE))
                .isEqualTo(feed.lastSnapshot(LIVE).requestId())
                .isNotEqualTo(live.requestId());
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(pre.after());
        assertThat(feed.counters.failed())
                .as("given up, not failed: the follow-ups wait for no backoff")
                .isZero();
        assertThat(feed.counters.abandoned()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aResetTheTransportCouldNotTakeLeavesTheRecoveriesTheSessionDidNotWaitForInFlight() {
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
        feed.accept(live);
        assertThat(feed.resets).containsExactly(2);

        // the queue kept what the recoveries sent, and session 1, whose snapshot completes were not ignored,
        // still waits for them: they stay
        feed.resetRefused();
        assertThat(feed.machine.inFlightRecovery(PRE)).isEqualTo(pre.requestId());
        assertThat(feed.machine.inFlightRecovery(LIVE)).isEqualTo(live.requestId());
        assertThat(feed.calls).hasSize(before + 2);
        feed.machine.snapshotComplete(1, PRE, pre.requestId());
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.counters.failed()).isZero();
    }

    @Test
    void aProducerDisabledOnTheVerySampleThatCrossesTheWindowStartsNoResetOfTheOthers() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofSeconds(60), () -> false);
        // the client's callback disables the producer as the sample that crosses the window is handled
        feed.producers.setProducerState(PRE, false);
        stale(feed, 1, OLD, Duration.ofSeconds(1), () -> false);
        assertThat(feed.calls)
                .as("nothing asked for live on a sample of a disabled producer")
                .hasSize(before);
        assertThat(feed.events).as("no lagging either").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    @Test
    void aLoneLowPrioritySessionNoOneTakesSnapshotCompletesForIsResetAndAsksAgainAfterIt() {
        Harness feed = new Harness();
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp();
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("both asked for").hasSize(before + 2);
        // each ends with its acceptance, with nothing to wait for
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        assertThat(feed.events).as("no failed request").isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();
        feed.resetDone();
        assertThat(feed.events).containsExactly("session 1 reset for producer 1");
        assertThat(feed.calls).as("both asked for again").hasSize(before + 4);
    }

    @Test
    void aSilentProducerOrAnUnsubscribedAliveCancelsTheResetWithTheNetsBackoff() {
        // the producer goes silent while the reset waits
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        liveAlives = false;
        // the inactivity tick gives live's recovery up, and the reset with it
        assertThat(stale(feed, 1, OLD, Duration.ofSeconds(60), () -> feed.machine.inFlightRecovery(LIVE) == 0))
                .isPositive();
        long cancelledAt = feed.now();
        // prematch's request, still pending, is refused: the reset is gone already, so there is nothing to report
        feed.refuse(pre);
        assertThat(feed.events).as("no failed request of the net's").isEmpty();
        assertThat(feed.counters.resetRequestsFailed()).isZero();
        assertThat(feed.resets).isEmpty();
        liveAlives = true;
        feed.alive(LIVE);
        assertThat(feed.lastSnapshot(LIVE).requestId()).isNotEqualTo(live.requestId());
        feed.complete(feed.lastSnapshot(LIVE), 1);
        int after = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(15), () -> feed.calls.size() > after);
        assertThat(feed.now() - cancelledAt)
                .as("from the inactivity tick to the net's next request: the ten minutes' backoff, then the window")
                .isBetween(
                        Duration.ofMinutes(10).toMillis(),
                        Duration.ofMinutes(11).toMillis());
    }

    @Test
    void anUnsubscribedAliveAfterTheAcceptanceCancelsTheResetWithTheNetsBackoff() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.unsubscribed(PRE);
        assertThat(feed.machine.inFlightRecovery(PRE)).isZero();
        feed.complete(feed.lastSnapshot(LIVE), 1);
        // the next alive asks for prematch again, for its own gap; it completes
        stale(feed, 1, OLD, Duration.ofSeconds(30), () -> feed.machine.inFlightRecovery(PRE) != 0);
        feed.complete(feed.lastSnapshot(PRE), 1);
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(15), () -> feed.calls.size() > after))
                .as("seconds to the net's next request after the give-up: past the ten minutes' backoff")
                .isGreaterThan(500);
    }

    @Test
    void aProducerWhoseCheckpointLiesOutsideTheCutoffMakesTheSessionLagWhileTheTriggeringOnesIsInside() {
        // a four minute window, a margin of two: a checkpoint is outside once it is more than two minutes back
        Harness feed = new Harness(Harness.settings(), false, 4);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        quiet(feed, 100);
        // prematch's messages are old, but its checkpoint moves with them; live's stays 161 s back
        takeStaleWithCheckpoints(feed, 61);
        assertThat(feed.calls).as("nothing is asked for").hasSize(before);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.events).containsExactly("session 1 lagging");
        assertThat(feed.machine.lagging(1)).isTrue();
    }

    @Test
    void aRecoveryThatReplacesOneThatEndedOnItsAcceptanceCancelsTheResetAtTheLastAcceptance() {
        Harness feed = new Harness();
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp();
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        assertThat(feed.machine.inFlightRecovery(PRE))
                .as("ended with its acceptance")
                .isZero();
        // prematch stops sending: a gap opens, and a recovery of its own, not the one the net asked for, starts
        feed.unsubscribed(PRE);
        assertThat(feed.machine.inFlightRecovery(PRE)).isNotZero().isNotEqualTo(pre.requestId());
        feed.accept(live);
        assertThat(feed.resets)
                .as("the recovery is no longer the one the net asked for")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.failureReasons).as("the check that cancelled it").containsExactly("recovery changed");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
    }

    @Test
    void anAcceptedRecoveryThatTimesOutWhileTheOtherIsPendingCancelsTheResetOnceWithTheNetsBackoff() {
        Harness feed = withSlowBackoff();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        // live's snapshot messages keep arriving, so only prematch's recovery goes quiet and times out
        for (int second = 1; second <= 6 * 60; second++) {
            feed.runWithAlives(Duration.ofSeconds(1));
            if (second % 30 == 0) {
                feed.machine.processed(1, LIVE, feed.now(), Stamp.of(feed.now()), live.requestId());
            }
        }
        assertThat(feed.counters.timedOut()).isEqualTo(1);
        assertThat(feed.events).as("one cancellation").containsExactly("session 1 not reset for producer 1");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
        feed.accept(live);
        assertThat(feed.resets)
                .as("nothing to make once the remaining request is accepted")
                .isEmpty();
        assertThat(feed.events).hasSize(1);

        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        int after = feed.calls.size();
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(15), () -> feed.calls.size() > after))
                .as("seconds to the net's next request: past the ten minutes' backoff")
                .isGreaterThan(500);
    }

    @Test
    void aProducerThatWentSilentAfterItsAcceptanceCancelsTheResetAtTheLastAcceptance() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        // prematch has sent nothing for longer than the maximum inactivity, and no tick has run since; live is alive
        feed.clock.advance(Duration.ofSeconds(25));
        feed.alive(LIVE);
        feed.accept(live);
        assertThat(feed.resets)
                .as("prematch could not be asked for again after the reset")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.failureReasons).containsExactly("producer cannot be asked");
        assertThat(feed.counters.resetRequestsFailed()).isEqualTo(1);
    }

    @Test
    void aReplacementRecoveryRefusedBeforeTheLastAcceptanceCancelsTheResetAsChanged() {
        Harness feed = new Harness();
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp();
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(feed.lastSnapshot(PRE));
        // a recovery of prematch's own starts, and is refused: the producer is in its retry backoff
        feed.unsubscribed(PRE);
        feed.refuse(feed.lastSnapshot(PRE));
        assertThat(feed.machine.inFlightRecovery(PRE)).isZero();
        feed.accept(live);
        assertThat(feed.resets)
                .as("with prematch waiting to be asked again, what the reset drops is not repeated at once")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.failureReasons)
                .as("the recovery is not the one asked for")
                .containsExactly("recovery changed");
    }

    @Test
    void aCheckpointAfterTheProducersClockMakesTheNetWaitUntilTheClockHasCaughtUp() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        takeStaleWithCheckpoints(feed, 5);
        // the producers' clock steps back ten minutes: the checkpoints are after its now
        preSkew = Duration.ofMinutes(10).toMillis();
        liveSkew = preSkew;
        aliveBoth(feed);
        long seconds = stale(feed, 1, OLD, Duration.ofMinutes(20), () -> feed.calls.size() > before);
        assertThat(feed.events).as("it waited, with no lagging").isEmpty();
        assertThat(seconds)
                .as("seconds to the net's request: not before the producers' clock is past the checkpoints")
                .isBetween(500L, 620L);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(before + 2);
    }

    @Test
    void aCheckpointAfterTheProducersClockAtTheLastAcceptanceCancelsTheReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        // the producers' clock steps back ten minutes before the API's answers come
        preSkew = Duration.ofMinutes(10).toMillis();
        liveSkew = preSkew;
        aliveBoth(feed);
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets)
                .as("a recovery is asked from the producer's now, before the checkpoint")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
        assertThat(feed.failureReasons)
                .as("the check that cancelled it")
                .containsExactly("checkpoint ahead of the producer");
    }

    @Test
    void aRecoveryThatPassesTheMaximumRecoveryTimeWhileItsSessionIsBeingResetIsGivenUpAndAskedAgainAtOnce() {
        Harness feed = new Harness(Harness.settings(Duration.ofMinutes(2), Duration.ofMinutes(10)));
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);

        // the transport takes longer than the maximum recovery time, and the recoveries run out of it
        feed.runWithAlives(Duration.ofSeconds(125));
        assertThat(feed.machine.inFlightRecovery(PRE)).as("given up").isZero();
        assertThat(feed.counters.timedOut()).isZero();
        assertThat(feed.counters.failed())
                .as("no failure, so no retry backoff and no cap")
                .isZero();
        feed.resetDone();
        assertThat(feed.calls)
                .as("both asked for again in the step the reset is done")
                .hasSize(before + 4);
    }

    @Test
    void aProducerEnabledWhileASessionThatReceivesItIsBeingResetIsDownForTheReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        // disabled just before the net acts, so no alive of it has been missed for long when it is enabled again
        stale(feed, 1, OLD, Duration.ofSeconds(55), () -> false);
        feed.producers.setProducerState(LIVE, false);
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("disabled, so not told down")
                .isFalse();

        feed.producers.setProducerState(LIVE, true);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("enabled while the session's queue is being replaced")
                .isTrue();
        assertThat(requireNonNull(feed.lastStatus(LIVE)).cause()).isEqualTo(StatusCause.SAFETY_NET_RESET);
        feed.resetDone();
        feed.alive(LIVE);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("until the recovery after the reset")
                .isTrue();
        feed.complete(feed.lastSnapshot(LIVE), 1);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aProducerEnabledDuringAResetStaysDownWhenAnotherSessionCompletesItsOldRecovery() {
        Harness feed = new Harness();
        // the low-priority session waits for no snapshot complete: the high-priority one completes them
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.open(2, MessageInterest.HI_PRIORITY_ONLY);
        feed.start();
        feed.bothUp(2);
        // live has a recovery of its own in flight when it is disabled
        feed.unsubscribed(LIVE);
        Outbox.Call.Snapshot old = feed.lastSnapshot(LIVE);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofSeconds(55), () -> false);
        feed.producers.setProducerState(LIVE, false);
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);

        feed.producers.setProducerState(LIVE, true);
        feed.advance(Duration.ofSeconds(1));
        feed.complete(old, 2);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("its old recovery is complete, but session 1's queue is being replaced")
                .isTrue();
        feed.resetDone();
        feed.alive(LIVE);
        feed.complete(feed.lastSnapshot(LIVE), 1, 2);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aSessionStopsLaggingWhenTheOnlyLaneStillOverTheLimitBelongsToADisabledProducer() {
        Harness feed = laggingWithBothLanesStale();
        feed.producers.setProducerState(LIVE, false);
        feed.advance(Duration.ofSeconds(1));
        // a sample of the disabled producer still reaches the net, and puts its lane over the limit again
        feed.machine.processed(1, LIVE, feed.now() - OLD.toMillis(), Stamp.of(feed.now()), 0);
        feed.live(1, PRE, FRESH);
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.events).containsExactly("session 1 lagging", "session 1 caught up");
    }

    @Test
    void disablingAProducerForgetsItsLanesStaleWindowSoItIsNotOverTheLimitWhenEnabledAgain() {
        Harness feed = laggingWithBothLanesStale();
        feed.producers.setProducerState(LIVE, false);
        feed.advance(Duration.ofSeconds(1));
        feed.producers.setProducerState(LIVE, true);
        feed.live(1, PRE, FRESH);
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    @Test
    void aLiveProducerAsTheTriggerAsksBothProducersAndResets() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        staleOf(feed, 1, LIVE, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        assertThat(feed.calls).as("a recovery for each producer").hasSize(before + 2);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
        feed.resetDone();
        assertThat(feed.calls).as("both asked for again").hasSize(before + 4);
    }

    @Test
    void aLiveProducerDisabledOnTheVerySampleThatCrossesTheWindowStartsNoResetOfTheOthers() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        staleOf(feed, 1, LIVE, OLD, Duration.ofSeconds(60), () -> false);
        feed.producers.setProducerState(LIVE, false);
        staleOf(feed, 1, LIVE, OLD, Duration.ofSeconds(1), () -> false);
        assertThat(feed.calls).hasSize(before);
        assertThat(feed.events).isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    /** Session 1, with a stateful window of a minute, stale on both producers and lagging for the window's sake. */
    private Harness laggingWithBothLanesStale() {
        Harness feed = new Harness(Harness.settings(), false, 1);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        for (int second = 1; second <= 70; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                aliveBoth(feed);
            }
            long now = feed.now();
            feed.machine.processed(1, PRE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.processed(1, LIVE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.tick();
        }
        assertThat(feed.machine.lagging(1)).isTrue();
        return feed;
    }

    @Test
    void aProducerDownForTheProcessingDelayThatIsEnabledDuringAResetDoesNotComeUpBeforeTheRecoveryAfterIt() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        // live's messages come late for a few seconds: it is down for the delay
        for (int second = 1; second <= 5; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            long now = feed.now();
            feed.machine.processed(1, LIVE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.processed(1, PRE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.tick();
        }
        assertThat(requireNonNull(feed.lastStatus(LIVE)).cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        stale(feed, 1, OLD, Duration.ofSeconds(50), () -> false);
        feed.producers.setProducerState(LIVE, false);
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.resets).containsExactly(1);

        // enabled while session 1's queue is being replaced, and its messages come on time again
        feed.producers.setProducerState(LIVE, true);
        feed.live(1, LIVE, FRESH);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("on time again, but the recovery after the reset has not completed")
                .isTrue();
        feed.resetDone();
        feed.alive(LIVE);
        feed.complete(feed.lastSnapshot(LIVE), 1);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void aReplacementRecoveryThatAlsoEndedOnItsAcceptanceDoesNotPassTheMarkerOfTheFirst() {
        Harness feed = new Harness();
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.bothUp();
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(feed.lastSnapshot(PRE));
        // an ordinary recovery of prematch starts, and ends with its acceptance as well, before live answers
        feed.unsubscribed(PRE);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.machine.inFlightRecovery(PRE)).isZero();
        feed.accept(live);
        assertThat(feed.resets)
                .as("a different recovery of prematch has run since the net asked")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 1");
    }

    @Test
    void disablingTheLastStaleLaneOfALaggingSessionEndsTheLagging() {
        // a one minute window: the session lags, with its single lane over the limit
        Harness feed = new Harness(Harness.settings(), false, 1);
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> false);
        assertThat(feed.machine.lagging(1)).isTrue();

        feed.producers.setProducerState(PRE, false);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.machine.lagging(1)).as("nothing is left to lag on").isFalse();
        assertThat(feed.events).containsExactly("session 1 lagging", "session 1 caught up");
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.events).as("told once").hasSize(2);
    }

    @Test
    void aLiveProducerWhoseClockStepsBackWhilePrematchTriggersHoldsTheNetBack() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        takeStaleWithCheckpoints(feed, 5);
        // only live's clock steps back ten minutes; prematch's checkpoint is behind its own clock
        liveSkew = Duration.ofMinutes(10).toMillis();
        aliveBoth(feed);
        takeStaleWithCheckpoints(feed, 5);
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before))
                .as("seconds to a request while live's checkpoint is after its clock")
                .isEqualTo(-1);
        assertThat(feed.events).as("it waits, with no lagging").isEmpty();
        assertThat(feed.machine.lagging(1)).isFalse();
    }

    @Test
    void aLiveProducerWhoseClockStepsBackBeforeTheLastAcceptanceCancelsTheResetWhilePrematchIsInside() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        liveSkew = Duration.ofMinutes(10).toMillis();
        aliveBoth(feed);
        feed.accept(pre);
        feed.accept(live);
        assertThat(feed.resets)
                .as("live's recovery is asked from its now, before its checkpoint")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.failureReasons)
                .as("the check that cancelled it")
                .containsExactly("checkpoint ahead of the producer");
    }

    @Test
    void
            anUnsubscribedAliveWhilePreflightRequestsAreOutstandingLeavesThemInFlightAndTheLastAcceptanceCancelsTheReset() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        Outbox.Call.Snapshot pre = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot live = feed.lastSnapshot(LIVE);
        feed.accept(pre);
        // live stops sending before the API has answered for it: a gap opens, and the request stays in flight
        feed.unsubscribed(LIVE);
        assertThat(feed.machine.inFlightRecovery(LIVE))
                .as("not given up: not accepted yet")
                .isEqualTo(live.requestId());
        assertThat(feed.events).as("nothing cancelled it").isEmpty();
        feed.accept(live);
        assertThat(feed.resets)
                .as("live's own recovery asks from the older gap and goes first")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 1 not reset for producer 2");
        assertThat(feed.failureReasons).containsExactly("producer has a gap open");
    }

    @Test
    void anUnsubscribedAliveBeforeASiblingsSnapshotCompleteSuppliesTheLastAcceptanceCancelsTheReset() {
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
        feed.unsubscribed(LIVE);
        // the high-priority session's snapshot complete says the API took it, before the REST answer
        feed.machine.snapshotComplete(1, LIVE, live.requestId());
        assertThat(feed.resets)
                .as("the gap the unsubscribe opened is older than the checkpoints say")
                .isEmpty();
        assertThat(feed.events).containsExactly("session 2 not reset for producer 2");
        assertThat(feed.failureReasons).containsExactly("producer has a gap open");
    }

    @Test
    void aLaggingSessionWhoseLaneWasNotOverTheLimitStopsLaggingWhenItsProducerIsDisabled() {
        Harness feed = new Harness(Harness.settings(), false, 1);
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> false);
        assertThat(feed.machine.lagging(1)).isTrue();
        // a recovery of the producer's own is in flight, and the session's next sample clears its lane
        feed.unsubscribed(PRE);
        feed.live(1, PRE, OLD);
        assertThat(feed.machine.lagging(1)).as("nothing yet says it caught up").isTrue();

        feed.producers.setProducerState(PRE, false);
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.machine.lagging(1)).isFalse();
        assertThat(feed.events).containsExactly("session 1 lagging", "session 1 caught up");
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.events).as("told once").hasSize(2);
    }

    // ---- a stale clock offset

    @Test
    void aStaleWindowThatBeganBeforeTheProducersOffsetWentStaleDoesNotCountThatTime() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        int before = feed.calls.size();
        // alives every two seconds, so that the offset is stale after a few seconds without one
        for (int second = 1; second <= 50; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            long now = feed.now();
            if (second % 2 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            feed.machine.processed(1, PRE, now - OLD.toMillis(), Stamp.of(now), 0);
            feed.machine.tick();
        }
        assertThat(feed.calls).as("not yet").hasSize(before);
        // no alive for fifteen seconds, and the session takes nothing of the producer meanwhile
        for (int second = 1; second <= 15; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            feed.machine.tick();
        }
        feed.alive(PRE);
        feed.alive(LIVE);
        feed.clock.advance(Duration.ofSeconds(1));
        feed.machine.tick();

        long now = feed.now();
        feed.machine.processed(1, PRE, now - OLD.toMillis(), Stamp.of(now), 0);
        assertThat(feed.calls)
                .as("a window of its own, not the one from before")
                .hasSize(before);
        assertThat(stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before))
                .as("seconds to the net's request: a window from the first sample after the alive")
                .isGreaterThan(55);
    }

    // ---- the margin on the recovery window

    @Test
    void aCheckpointInsideTheWindowButWithinTheMarginOfItsStartIsNotReset() {
        // a two minute window, a margin of a minute: the checkpoint, a minute back, is inside it and within it
        Harness feed = new Harness(Harness.settings(), false, 2);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> false);
        assertThat(feed.calls).as("nothing asked for").hasSize(before);
        assertThat(feed.resets).isEmpty();
        assertThat(feed.machine.lagging(1)).isTrue();
    }

    @Test
    void aCheckpointAMarginOfTenMinutesFromTheWindowsStartIsNotReset() {
        // an hour's window: the margin is ten minutes, not half the window; the net acts 61 s after the quiet time
        Harness feed = new Harness(Harness.settings(), false, 60);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        // the checkpoint is 51 minutes back when the net acts: nine minutes from the window's start
        quiet(feed, 51 * 60 - 61);
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> false);
        assertThat(feed.calls).as("nothing asked for").hasSize(before);
        assertThat(feed.machine.lagging(1)).isTrue();
    }

    @Test
    void aCheckpointJustOutsideTheMarginOfTenMinutesOfALongWindowIsResetThoughWithinHalfTheWindow() {
        // an hour's window, half of it thirty minutes: the checkpoint 49 minutes back is eleven minutes from the
        // window's start, outside the ten minutes' margin and inside half the window
        Harness feed = new Harness(Harness.settings(), false, 60);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        quiet(feed, 49 * 60 - 61);
        stale(feed, 1, OLD, Duration.ofMinutes(3), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
    }

    @Test
    void theMarginIsAtMostHalfTheWindow() {
        // a three minute window: the margin is a minute and a half, not ten minutes; the checkpoint is a minute back
        Harness feed = new Harness(Harness.settings(), false, 3);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        stale(feed, 1, OLD, Duration.ofMinutes(5), () -> feed.calls.size() > before);
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        assertThat(feed.resets).containsExactly(1);
    }

    @Test
    void theNetResetsASessionThreeTimesPerCooldownWithBackoffThenTheSessionLags() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        long start = feed.now();
        List<Long> resetAt = resetUntilTheSessionLags(feed, start);
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
    void aLaggingSessionKeepsTheProducerDownForTheProcessingDelayUntilItIsOnTimeAgain() {
        Harness feed = Harness.upWith(MessageInterest.ALL);
        resetUntilTheSessionLags(feed, feed.now());
        assertThat(feed.machine.lagging(1)).isTrue();

        // the net takes no producer down, the processing delay does: the session takes it three minutes late
        ProducerStatusChange late = requireNonNull(feed.lastStatus(PRE));
        assertThat(late.down()).isTrue();
        assertThat(late.cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        int changes = feed.statuses(PRE).size();
        stale(feed, 1, OLD, Duration.ofMinutes(2), () -> false);
        assertThat(feed.machine.lagging(1)).isTrue();
        assertThat(feed.statuses(PRE)).as("down all the while it lags").hasSize(changes);

        // on time again: the lag clears and the producer is back
        stale(feed, 1, FRESH, Duration.ofSeconds(1), () -> false);
        assertThat(feed.machine.lagging(1)).isFalse();
        ProducerStatusChange back = requireNonNull(feed.lastStatus(PRE));
        assertThat(back.down()).isFalse();
        assertThat(back.cause()).isEqualTo(StatusCause.DELAY_STABILIZED);
        assertThat(feed.statuses(PRE)).hasSize(changes + 1);
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
                .as("seconds to the net's next request: the window starts again when the recovery ends")
                .isEqualTo(61);
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
                .as("seconds to the net's next request: the window starts again when the recovery ends")
                .isEqualTo(61);
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

    /** The net's resets, each one taken and finished, until session 1 lags: the seconds of each from {@code start}. */
    private List<Long> resetUntilTheSessionLags(Harness feed, long start) {
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
        return resetAt;
    }

    /**
     * Second by second, session {@code session} takes a live message of producer 1 that is {@code
     * age} old by that producer's clock, with both producers' alives every ten seconds of the
     * clock, until {@code until} holds.
     *
     * @return the seconds that took, or -1 when it did not hold within {@code limit}
     */
    private long stale(Harness feed, int session, Duration age, Duration limit, BooleanSupplier until) {
        return stale(feed, session, age, limit, until, false);
    }

    /**
     * As {@link #stale(Harness, int, Duration, Duration, BooleanSupplier)}, but {@code until} is
     * looked at right after the message, before the second's tick: what the message itself did is
     * all there is.
     */
    private long staleBeforeTheTick(Harness feed, int session, Duration age, Duration limit, BooleanSupplier until) {
        return stale(feed, session, age, limit, until, true);
    }

    private long stale(
            Harness feed, int session, Duration age, Duration limit, BooleanSupplier until, boolean beforeTheTick) {
        return stale(feed, session, PRE, age, limit, until, beforeTheTick);
    }

    /** As {@link #stale(Harness, int, Duration, Duration, BooleanSupplier)}, with messages of {@code producer}. */
    private long staleOf(
            Harness feed, int session, long producer, Duration age, Duration limit, BooleanSupplier until) {
        return stale(feed, session, producer, age, limit, until, false);
    }

    private long stale(
            Harness feed,
            int session,
            long producer,
            Duration age,
            Duration limit,
            BooleanSupplier until,
            boolean beforeTheTick) {
        for (long second = 1; second <= limit.toSeconds(); second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (alives && Duration.between(Harness.START, feed.clock.instant()).toSeconds() % 10 == 0) {
                aliveBoth(feed);
            }
            long now = feed.now();
            long skew = producer == PRE ? preSkew : liveSkew;
            feed.machine.processed(session, producer, now - skew - age.toMillis(), Stamp.of(now), requestId);
            if (beforeTheTick && until.getAsBoolean()) {
                return second;
            }
            feed.machine.tick();
            if (until.getAsBoolean()) {
                return second;
            }
        }
        return -1;
    }

    /**
     * Second by second, session 1 takes a live message of producer 1 that is old by that producer's
     * clock, as {@link #stale} does, but one that moves its checkpoint to now, with the alives every ten seconds.
     */
    private void takeStaleWithCheckpoints(Harness feed, int seconds) {
        for (int second = 1; second <= seconds; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                aliveBoth(feed);
            }
            long now = feed.now();
            feed.machine.processed(1, PRE, now - OLD.toMillis(), now, Stamp.of(now), 0);
            feed.machine.tick();
        }
    }

    /** The clock moves, with the alives of both producers every ten seconds and nothing taken by the session. */
    private void quiet(Harness feed, int seconds) {
        for (int second = 1; second <= seconds; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (Duration.between(Harness.START, feed.clock.instant()).toSeconds() % 10 == 0) {
                aliveBoth(feed);
            }
            feed.machine.tick();
        }
    }

    /** The design's numbers, but ten minutes before the net tries again after a reset it did not make. */
    private static Harness withSlowBackoff() {
        RecoverySettings design = Harness.settings();
        return new Harness(new RecoverySettings(
                design.maxInactivity(),
                design.maxRecoveryTime(),
                design.snapshotCompleteTimeout(),
                design.initialSnapshotInterval(),
                design.nodeId(),
                design.reissues(),
                design.firstReissueBackoff(),
                design.cooldown(),
                design.aliveInterval(),
                design.staleLimit(),
                design.staleWindow(),
                design.resets(),
                Duration.ofMinutes(10),
                design.eventRecoveries(),
                design.tick()));
    }

    private void aliveBoth(Harness feed) {
        long now = feed.now();
        feed.machine.alive(PRE, now - preSkew, Stamp.of(now), true);
        if (liveAlives) {
            feed.machine.alive(LIVE, now - liveSkew, Stamp.of(now), true);
        }
    }
}
