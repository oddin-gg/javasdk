package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.MATCH;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatusReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The recovery state machine, rule by rule, on a clock the test moves: when a recovery is asked
 * for and from where, what completes it, what joins it, what gives it up, how often it is asked for
 * again, and what the client hears.
 */
class RecoveryMachineTest {

    private final Harness feed = new Harness();

    // ---- the first recovery

    @Test
    void theFirstAliveAsksForAFullSnapshotAndItsCompletionBringsTheProducerUp() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.advance(Duration.ofSeconds(5));
        assertThat(feed.calls).as("requests before any alive").isEmpty();
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();

        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        assertThat(recovery.producer()).isEqualTo("pre");
        assertThat(recovery.after()).as("a cold start with no recovery point").isNull();
        assertThat(feed.snapshots(LIVE)).as("producer 2 has sent no alive").isEmpty();
        assertThat(feed.statuses)
                .as("status changes while the first recovery runs")
                .isEmpty();

        feed.complete(recovery, 1);
        ProducerStatusChange up = requireNonNull(feed.lastStatus(PRE));
        assertThat(up.down()).isFalse();
        assertThat(up.cause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);
        assertThat(up.reason()).isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
        assertThat(up.timestamp()).isEqualTo(feed.now());
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        RecoveryInfo info =
                requireNonNull(requireNonNull(feed.producers.getProducer(PRE)).getRecoveryInfo());
        assertThat(info.getRequestId()).isEqualTo(recovery.requestId());
        assertThat(info.getSuccessful()).isTrue();
        assertThat(info.getNodeId()).isEqualTo(Harness.NODE);
        assertThat(info.getAfter()).as("a full snapshot").isZero();
        assertThat(feed.counters.completed()).isEqualTo(1);
    }

    @Test
    void theClientsRecoveryPointSeedsTheFirstRecovery() {
        long from = feed.now() - Duration.ofHours(1).toMillis();
        feed.producers.setProducerRecoveryFromTimestamp(PRE, from);
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(Instant.ofEpochMilli(from));
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(from);
    }

    @Test
    void aRecoveryPointIsClampedToTheStatefulRecoveryWindow() {
        // inside the window when the client set it, outside once the feed has run an hour
        long from = feed.now()
                - Duration.ofDays(3).toMillis()
                + Duration.ofMinutes(1).toMillis();
        feed.producers.setProducerRecoveryFromTimestamp(PRE, from);
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.clock.advance(Duration.ofHours(1));
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after())
                .isEqualTo(Instant.ofEpochMilli(feed.now() - Duration.ofDays(3).toMillis()));
    }

    @Test
    void aColdStartWithAnInitialSnapshotIntervalAsksThatFarBack() {
        var settings = Harness.settings();
        var withInterval = new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
                Duration.ofMinutes(30),
                settings.nodeId(),
                settings.reissues(),
                settings.firstReissueBackoff(),
                settings.cooldown(),
                settings.aliveInterval(),
                settings.staleLimit(),
                settings.staleWindow(),
                settings.resets(),
                settings.firstResetBackoff(),
                settings.eventRecoveries(),
                settings.tick());
        var feed = new Harness(withInterval);
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.clock.advance(Duration.ofSeconds(3));
        feed.alive(PRE);
        Instant boundary = Instant.ofEpochMilli(
                        feed.now() - Duration.ofSeconds(3).toMillis())
                .minus(Duration.ofMinutes(30));
        assertThat(feed.lastSnapshot(PRE).after())
                .as("from when the feed opened")
                .isEqualTo(boundary);

        // asked for again a minute later: the boundary stays where it was
        feed.refuse(feed.lastSnapshot(PRE));
        feed.runWithAlives(Duration.ofMinutes(1));
        assertThat(feed.snapshots(PRE)).hasSize(2);
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(boundary);
    }

    @Test
    void nothingIsAskedForBeforeStartOrWithoutASessionToReceiveIt() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.alive(PRE);
        assertThat(feed.calls).as("before start").isEmpty();
        feed.machine.start();
        feed.alive(LIVE);
        assertThat(feed.snapshots(LIVE))
                .as("no session takes the live producer")
                .isEmpty();
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(1);
    }

    // ---- completion per session

    @Test
    void aProducerIsUpOnceEverySessionThatReceivesItHasSeenItsSnapshotComplete() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.open(3, MessageInterest.HI_PRIORITY_ONLY);
        // next to a high-priority session the low-priority one takes no snapshot completes
        feed.open(new SessionInfo(4, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);

        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        feed.machine.snapshotComplete(2, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE))
                .as("down with the high-priority session yet to see it")
                .isTrue();
        feed.machine.snapshotComplete(3, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.statuses(PRE)).hasSize(1);
        assertThat(feed.counters.unknownCompletions())
                .as("the live-only session's completion is no unknown id")
                .isZero();
    }

    @Test
    void aCompletionForAnIdNotInFlightIsIgnoredAndCounted() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        feed.alive(LIVE);
        feed.machine.snapshotComplete(1, PRE, 1);
        feed.machine.snapshotComplete(1, PRE, feed.lastSnapshot(LIVE).requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        assertThat(feed.producers.isProducerDown(LIVE)).isTrue();
        assertThat(feed.counters.unknownCompletions()).isEqualTo(2);
    }

    @Test
    void aSnapshotCompleteQuickerThanTheApisAnswerCompletesTheRecovery() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(requireNonNull(
                                requireNonNull(feed.producers.getProducer(PRE)).getRecoveryInfo())
                        .getSuccessful())
                .isTrue();
        feed.accept(recovery);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    // ---- checkpoints

    @Test
    void aSessionsCheckpointIsTheMostRecentLiveMessageOrSubscribedAliveItFinished() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        long requestedAt = feed.now();
        assertThat(feed.machine.checkpoint(1, PRE))
                .as("having seen the snapshot complete, it has everything up to the request")
                .isEqualTo(requestedAt);

        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 10_000, t, false);
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(t - 10_000);
        feed.machine.processed(1, PRE, t - 50_000, t, false);
        assertThat(feed.machine.checkpoint(1, PRE)).as("an older message").isEqualTo(t - 10_000);
        feed.machine.processed(1, PRE, t, t, true);
        assertThat(feed.machine.checkpoint(1, PRE)).as("a snapshot message").isEqualTo(t - 10_000);
        feed.machine.sessionAlive(1, PRE, t - 5_000, t, false);
        assertThat(feed.machine.checkpoint(1, PRE)).as("an unsubscribed alive").isEqualTo(t - 10_000);
        feed.machine.sessionAlive(1, PRE, t - 5_000, t, true);
        assertThat(feed.machine.checkpoint(1, PRE)).as("a subscribed alive").isEqualTo(t - 5_000);
        assertThat(feed.machine.checkpoint(1, LIVE)).as("the other producer").isEqualTo(requestedAt);
    }

    @Test
    void aLostConnectionRecoversFromTheOldestCheckpointOfTheSessionsThatReceiveTheProducer() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.open(3, MessageInterest.LIVE_ONLY);
        feed.machine.start();
        feed.bothUp(1, 2, 3);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 10_000, t, false);
        feed.machine.processed(2, PRE, t - 40_000, t, false);
        feed.machine.processed(1, LIVE, t - 5_000, t, false);
        feed.machine.processed(3, LIVE, t - 2_000, t, false);

        feed.machine.connectionDown();
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.CONNECTION_LOST);
        assertThat(requireNonNull(feed.lastStatus(PRE)).reason()).isEqualTo(ProducerStatusReason.OTHER);
        assertThat(feed.producers.isProducerDown(LIVE)).isTrue();
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).as("while the connection is down").hasSize(1);

        feed.machine.connectionUp();
        feed.alive(PRE);
        feed.alive(LIVE);
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(Instant.ofEpochMilli(t - 40_000));
        assertThat(feed.lastSnapshot(LIVE).after()).isEqualTo(Instant.ofEpochMilli(t - 5_000));
    }

    @Test
    void aRecoveryAskedForAgainStartsFromTheSamePoint() {
        long from = feed.now() - Duration.ofHours(1).toMillis();
        feed.producers.setProducerRecoveryFromTimestamp(PRE, from);
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot first = feed.lastSnapshot(PRE);
        feed.accept(first);
        // live messages and alives go on while the snapshot comes, and some of it arrives
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 1_000, t, true);
        feed.machine.processed(1, PRE, t, t, false);
        feed.sessionAlive(1, PRE);
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(t);

        feed.refuse(first);
        feed.advance(Duration.ofSeconds(5));
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(first.requestId());
        assertThat(again.after()).isEqualTo(first.after()).isEqualTo(Instant.ofEpochMilli(from));
    }

    // ---- a producer that stops sending

    @Test
    void anUnsubscribedAliveRecoversFromTheLastSubscribedOneEvenWithSessionsBehind() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.clock.advance(Duration.ofMinutes(1));
        // the alive channel has seen these two; the session has not finished either yet
        long lastWhileUp = feed.now() - 2_000;
        feed.machine.alive(PRE, lastWhileUp, feed.now(), true);
        feed.machine.alive(PRE, lastWhileUp + 1_000, feed.now(), false);

        ProducerStatusChange down = requireNonNull(feed.lastStatus(PRE));
        assertThat(down.down()).isTrue();
        assertThat(down.cause()).isEqualTo(StatusCause.UNSUBSCRIBED);
        assertThat(down.reason()).isEqualTo(ProducerStatusReason.OTHER);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        assertThat(recovery.after()).isEqualTo(Instant.ofEpochMilli(lastWhileUp));

        feed.complete(recovery, 1);
        ProducerStatusChange back = requireNonNull(feed.lastStatus(PRE));
        assertThat(back.down()).isFalse();
        assertThat(back.cause()).isEqualTo(StatusCause.RECOVERY_COMPLETED);
        assertThat(back.reason()).isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
    }

    @Test
    void aProducerWithoutAlivesForTooLongIsDownUntilAnAliveBringsARecovery() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        long lastAlive = feed.now();
        for (int second = 0; second < 20; second++) {
            feed.advance(Duration.ofSeconds(1));
        }
        assertThat(feed.producers.isProducerDown(PRE))
                .as("at the maximum inactivity")
                .isFalse();
        feed.advance(Duration.ofSeconds(1));
        ProducerStatusChange down = requireNonNull(feed.lastStatus(PRE));
        assertThat(down.cause()).isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(down.reason()).isEqualTo(ProducerStatusReason.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.snapshots(PRE)).as("no recovery without an alive").hasSize(1);

        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        assertThat(recovery.after()).as("from the last alive").isEqualTo(Instant.ofEpochMilli(lastAlive));
        feed.complete(recovery, 1);
        assertThat(requireNonNull(feed.lastStatus(PRE)).reason())
                .isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
    }

    @Test
    void aProducerThatNeverSentAnAliveIsReportedDownOnceTheMaximumInactivityHasPassed() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        for (int second = 0; second < 21; second++) {
            feed.advance(Duration.ofSeconds(1));
        }
        assertThat(feed.statuses)
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.ALIVE_INTERVAL_VIOLATION, StatusCause.ALIVE_INTERVAL_VIOLATION);
        // nothing was received to miss: the start's own gap stands
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after()).isNull();
    }

    // ---- coalescing

    @Test
    void triggersWhileARecoveryIsInFlightJoinItAndWhatOpenedSinceAsksForOneMore() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        feed.unsubscribed(PRE);
        feed.alive(PRE);
        feed.open(2, MessageInterest.ALL);
        feed.advance(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).as("one in flight at a time").hasSize(2);

        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        Outbox.Call.Snapshot more = feed.lastSnapshot(PRE);
        assertThat(more.requestId())
                .as("one more, for what opened since it was asked for")
                .isNotEqualTo(recovery.requestId());
        assertThat(feed.snapshots(PRE)).hasSize(3);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();

        feed.complete(more, 1, 2);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.snapshots(PRE)).hasSize(3);
    }

    @Test
    void aProducerThatStopsSendingWhileARecoveryIsInFlightAsksForOneMoreAfterIt() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        // the producer still says unsubscribed after the request went out
        feed.unsubscribed(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(2);
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.snapshots(PRE)).as("one more").hasSize(3);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @Test
    void aLostConnectionGivesUpTheRecoveryInFlightWhoseSnapshotWentWithTheQueues() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot lost = feed.lastSnapshot(PRE);
        feed.accept(lost);
        feed.machine.connectionDown();
        feed.machine.connectionUp();
        feed.alive(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(lost.requestId());
        assertThat(feed.counters.abandoned()).isEqualTo(1);
        assertThat(feed.counters.failed()).as("not the API's failure").isZero();

        feed.machine.snapshotComplete(1, PRE, lost.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        assertThat(feed.counters.unknownCompletions()).isEqualTo(1);
        feed.complete(again, 1);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void aSessionsLostChannelRecoversFromItsCheckpointAndGivesUpARecoveryWaitingForIt() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1, 2);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 1_000, t, false);
        feed.machine.processed(2, PRE, t - 30_000, t, false);
        feed.alive(PRE);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot waiting = feed.lastSnapshot(PRE);
        feed.accept(waiting);
        feed.machine.snapshotComplete(1, PRE, waiting.requestId());

        feed.machine.channelLost(2);
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.CHANNEL_LOST);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(waiting.requestId());
        assertThat(again.after()).isEqualTo(Instant.ofEpochMilli(t - 30_000));
        assertThat(feed.counters.abandoned()).isEqualTo(1);
        assertThat(feed.snapshots(LIVE))
                .as("the live producer is asked for at its next alive")
                .hasSize(1);
        feed.alive(LIVE);
        assertThat(feed.snapshots(LIVE)).hasSize(2);
    }

    // ---- sessions opening and closing

    @Test
    void aSessionThatOpensStartsFromTheProducersRecoveryPointAndAsksForARecovery() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 5_000, t, false);

        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.alive(PRE);
        assertThat(feed.machine.checkpoint(2, PRE)).isEqualTo(t - 5_000);
        assertThat(feed.machine.checkpoint(2, LIVE)).as("not received").isEqualTo(-1);
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.SESSION_OPENED);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        assertThat(recovery.after()).isEqualTo(Instant.ofEpochMilli(t - 5_000));
        feed.accept(recovery);
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE))
                .as("waiting for the new session")
                .isTrue();
        feed.machine.snapshotComplete(2, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void aSessionThatClosesLeavesTheCompletionsAndTheCheckpointsAtOnce() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();

        feed.close(2);
        assertThat(feed.producers.isProducerDown(PRE))
                .as("the closed session was all it waited for")
                .isFalse();
        assertThat(feed.machine.checkpoint(2, PRE)).isEqualTo(-1);

        // the closed session's checkpoint no longer holds a recovery back
        feed.machine.processed(1, PRE, feed.now(), feed.now(), false);
        feed.machine.connectionDown();
        feed.machine.connectionUp();
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        // a late fact of the closed session changes nothing
        feed.machine.processed(2, PRE, 1, feed.now(), false);
        feed.machine.snapshotComplete(2, PRE, feed.lastSnapshot(PRE).requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @Test
    void aProducerDownOnlyForAClosedSessionsGapComesBack() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.clock.advance(Duration.ofMinutes(1));
        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(2);
        feed.refuse(feed.lastSnapshot(PRE));
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        feed.close(2);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.RECOVERY_COMPLETED);
    }

    // ---- asking again

    @Test
    void aFailedRecoveryIsAskedForAgainWithBackoffThreeTimesThenNotUntilTheCooldown() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        long[] pauses = {5, 10, 20};
        for (long pause : pauses) {
            feed.refuse(feed.lastSnapshot(PRE));
            int asked = feed.snapshots(PRE).size();
            feed.runWithAlives(Duration.ofSeconds(pause - 1));
            assertThat(feed.snapshots(PRE))
                    .as("before the pause of " + pause + " s")
                    .hasSize(asked);
            feed.runWithAlives(Duration.ofSeconds(1));
            assertThat(feed.snapshots(PRE))
                    .as("after the pause of " + pause + " s")
                    .hasSize(asked + 1);
        }
        feed.refuse(feed.lastSnapshot(PRE));
        ProducerStatusChange failed = requireNonNull(feed.lastStatus(PRE));
        assertThat(failed.cause()).isEqualTo(StatusCause.RECOVERY_FAILED);
        assertThat(failed.down()).isTrue();
        assertThat(feed.counters.failed()).isEqualTo(4);
        assertThat(feed.counters.reissued()).isEqualTo(3);

        feed.runWithAlives(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(feed.snapshots(PRE))
                .as("during the cool-down, alives or not")
                .hasSize(4);
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.snapshots(PRE)).as("re-armed by the cool-down").hasSize(5);
        // re-armed: three more tries before the cap is spent again
        feed.refuse(feed.lastSnapshot(PRE));
        feed.runWithAlives(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).hasSize(6);
    }

    @Test
    void anAliveAfterAGapReArmsASpentCapAtOnce() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        for (int attempt = 0; attempt < 4; attempt++) {
            feed.refuse(feed.lastSnapshot(PRE));
            feed.runWithAlives(Duration.ofSeconds(20));
        }
        assertThat(feed.snapshots(PRE)).hasSize(4);
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.RECOVERY_FAILED);

        for (int second = 0; second < 21; second++) {
            feed.advance(Duration.ofSeconds(1));
        }
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(5);
    }

    @Test
    void aRecoveryWithNoSnapshotCompleteWithinTheMaximumRecoveryTimeIsAskedForAgain() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        feed.runWithAlives(Duration.ofHours(6));
        assertThat(feed.snapshots(PRE)).as("at the maximum recovery time").hasSize(1);
        assertThat(feed.counters.timedOut()).as("at the maximum recovery time").isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);
        feed.runWithAlives(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).hasSize(2);
        assertThat(requireNonNull(
                                requireNonNull(feed.producers.getProducer(PRE)).getRecoveryInfo())
                        .getSuccessful())
                .as("the timed-out one")
                .isFalse();
    }

    // ---- what the client hears

    @Test
    void anAliveSayingAProducerStillDownIsUnsubscribedIsReportedWithItsCause() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        feed.alive(PRE);
        assertThat(feed.statuses).as("alives change no status").isEmpty();
        feed.unsubscribed(PRE);
        assertThat(feed.statuses).extracting(ProducerStatusChange::cause).containsExactly(StatusCause.UNSUBSCRIBED);
        feed.unsubscribed(PRE);
        assertThat(feed.statuses).as("the same cause again").hasSize(1);
        assertThat(feed.snapshots(PRE)).as("joined the one in flight").hasSize(1);
    }

    @Test
    void aDisabledProducerIsNotRecoveredAndAnUnknownOneIsCounted() {
        feed.producers.setProducerState(LIVE, false);
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(LIVE);
        feed.unsubscribed(LIVE);
        feed.machine.alive(9, feed.now(), feed.now(), true);
        feed.machine.processed(1, 9, feed.now(), feed.now(), false);
        assertThat(feed.calls).isEmpty();
        assertThat(feed.statuses).isEmpty();
        assertThat(feed.counters.unknownProducers()).isEqualTo(2);
    }

    @Test
    void aSessionThatProcessesLateTakesTheProducerDownWithoutARecoveryUntilItCatchesUp() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.live(1, PRE, Duration.ofSeconds(25));
        feed.advance(Duration.ofMillis(10));
        ProducerStatusChange late = requireNonNull(feed.lastStatus(PRE));
        assertThat(late.cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(late.reason()).isEqualTo(ProducerStatusReason.PROCESSING_QUEUE_DELAY_VIOLATION);
        assertThat(late.delayed()).isTrue();
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
        assertThat(feed.snapshots(PRE)).hasSize(1);

        feed.live(1, PRE, Duration.ofSeconds(1));
        feed.advance(Duration.ofMillis(10));
        ProducerStatusChange back = requireNonNull(feed.lastStatus(PRE));
        assertThat(back.down()).isFalse();
        assertThat(back.cause()).isEqualTo(StatusCause.DELAY_STABILIZED);
        assertThat(back.reason()).isEqualTo(ProducerStatusReason.RETURNED_FROM_INACTIVITY);
        assertThat(back.delayed()).isFalse();
    }

    @Test
    void aSessionThatTakesNothingWhileAlivesFlowIsLate() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.live(1, PRE, Duration.ZERO);
        // alives on the alive channel only: the session's own queue does not move
        for (int second = 1; second <= 21; second++) {
            feed.clock.advance(Duration.ofSeconds(1));
            if (second % 10 == 0) {
                feed.alive(PRE);
                feed.alive(LIVE);
            }
            feed.machine.tick();
        }
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.producers.isProducerDown(LIVE))
                .as("a producer the session has taken nothing of yet")
                .isFalse();
    }

    // ---- event recoveries

    @Test
    void anEventRecoveryAnswersWithItsRequestIdAndCompletesOnceItsSnapshotCompleteIsSeen()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.machine.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        var call = (Outbox.Call.Event) feed.calls.getLast();
        assertThat(call.producer()).isEqualTo("live");
        assertThat(call.eventId()).isEqualTo(MATCH);
        assertThat(call.stateful()).isFalse();
        assertThat(reply).as("before the API answers").isNotDone();
        feed.accept(call);
        assertThat(done(reply)).isEqualTo(call.requestId());

        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        assertThat(feed.events).isEmpty();
        feed.machine.snapshotComplete(2, LIVE, call.requestId());
        assertThat(feed.events).containsExactly("event recovery " + call.requestId() + " of " + MATCH + " completed");
        feed.machine.snapshotComplete(2, LIVE, call.requestId());
        assertThat(feed.events).as("once").hasSize(1);
        assertThat(feed.counters.unknownCompletions()).isEqualTo(1);
    }

    @Test
    void anEventRecoveryTheApiRefusedAnswersNull() throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(PRE);
        Outbox.Call call = feed.calls.getLast();
        feed.refuse(call);
        assertThat(done(reply)).isNull();
        assertThat(feed.counters.eventRefused()).isEqualTo(1);
        feed.machine.snapshotComplete(1, PRE, call.requestId());
        assertThat(feed.events).isEmpty();
    }

    @Test
    void anEventRecoveryOfAnUnknownProducerFails() {
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(9);
        assertThat(reply).isCompletedExceptionally();
        assertThat(feed.calls).isEmpty();
    }

    @Test
    void eventRecoveriesInFlightAreBoundedPerProducerAndExpire() throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        for (int i = 0; i < 128; i++) {
            assertThat(feed.recoverEvent(LIVE)).isNotDone();
            feed.accept(feed.calls.getLast());
        }
        assertThat(done(feed.recoverEvent(LIVE))).as("the 129th").isNull();
        assertThat(feed.recoverEvent(PRE)).as("another producer's").isNotDone();
        feed.advance(Duration.ofHours(6));
        assertThat(feed.counters.eventExpired())
                .as("at the maximum recovery time")
                .isZero();
        feed.advance(Duration.ofSeconds(1));
        assertThat(feed.counters.eventExpired())
                .as("128 of one producer, 1 of the other")
                .isEqualTo(129);
        assertThat(feed.recoverEvent(LIVE)).as("room again").isNotDone();
    }

    @Test
    void aLostConnectionGivesUpTheEventRecoveriesInFlightAndAnswersThoseStillWaiting()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        CompletableFuture<@Nullable Long> answered = feed.recoverEvent(LIVE);
        Outbox.Call call = feed.calls.getLast();
        feed.accept(call);
        CompletableFuture<@Nullable Long> waiting = feed.recoverEvent(LIVE);
        feed.machine.connectionDown();
        assertThat(done(answered)).isEqualTo(call.requestId());
        assertThat(done(waiting)).as("the API had not answered").isNull();
        assertThat(feed.counters.eventAbandoned()).isEqualTo(2);
        feed.machine.connectionUp();
        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        assertThat(feed.events).as("given up").isEmpty();
    }

    @Test
    void aSessionsLostChannelGivesUpTheEventRecoveriesWaitingForIt() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.machine.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call seenByBoth = feed.calls.getLast();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call seenByOne = feed.calls.getLast();
        feed.accept(seenByBoth);
        feed.accept(seenByOne);
        feed.machine.snapshotComplete(2, LIVE, seenByBoth.requestId());
        feed.machine.snapshotComplete(1, LIVE, seenByOne.requestId());

        feed.machine.channelLost(2);
        assertThat(feed.counters.eventAbandoned())
                .as("the one session 2 had yet to see")
                .isEqualTo(1);
        feed.machine.snapshotComplete(1, LIVE, seenByBoth.requestId());
        assertThat(feed.events)
                .containsExactly("event recovery " + seenByBoth.requestId() + " of " + MATCH + " completed");
    }

    @Test
    void closingTheLastSessionWhileTheConnectionIsDownKeepsTheProducerDownUntilItIsBack() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.bothUp(1);
        feed.machine.connectionDown();
        feed.close(1);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        feed.machine.connectionUp();
        assertThat(feed.producers.isProducerDown(PRE))
                .as("no session misses anything")
                .isFalse();
    }

    @Test
    void anEventRecoveryWhileTheConnectionIsDownIsRefused() throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.machine.connectionDown();
        assertThat(done(feed.recoverEvent(LIVE)))
                .as("its snapshot would have no queue to go to")
                .isNull();
        assertThat(feed.calls).isEmpty();
        assertThat(feed.counters.eventRefused()).isEqualTo(1);
        feed.machine.connectionUp();
        assertThat(feed.recoverEvent(LIVE)).as("once it is back").isNotDone();
        assertThat(feed.calls).hasSize(1);
    }

    @Test
    void aRecoveryNoSessionTakesSnapshotCompletesForCompletesWhenTheApiAcceptsIt()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.machine.start();
        feed.close(1);
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        assertThat(feed.producers.isProducerDown(PRE))
                .as("nothing would ever complete it")
                .isFalse();

        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        Outbox.Call event = feed.calls.getLast();
        feed.accept(event);
        assertThat(done(reply)).isEqualTo(event.requestId());
        assertThat(feed.events).containsExactly("event recovery " + event.requestId() + " of " + MATCH + " completed");
    }

    @Test
    void closingAnswersEveryoneStillWaitingWithNull() throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(PRE);
        feed.machine.close();
        assertThat(done(reply)).isNull();
    }

    /** The value of a future that must be complete by now: the machine answers on its own thread. */
    private static @Nullable Long done(CompletableFuture<@Nullable Long> reply)
            throws ExecutionException, InterruptedException {
        assertThat(reply).as("answered").isDone();
        return reply.get();
    }

    @Test
    void requestIdsAreUniqueAmongThoseInFlight() {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        feed.alive(LIVE);
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        List<Long> ids = feed.calls.stream().map(Outbox.Call::requestId).toList();
        assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isBetween(1L, RequestIds.MAX));
    }
}
