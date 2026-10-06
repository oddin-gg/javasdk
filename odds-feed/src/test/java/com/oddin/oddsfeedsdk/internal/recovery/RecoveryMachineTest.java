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
import java.util.ArrayList;
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
        feed.start();
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
        feed.start();
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
        feed.start();
        feed.clock.advance(Duration.ofHours(1));
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after())
                .isEqualTo(Instant.ofEpochMilli(feed.now() - Duration.ofDays(3).toMillis()));
    }

    @Test
    void theWindowIsCountedBackByTheProducersClockWhicheverWayItIsOff() {
        for (long offset : new long[] {
            Duration.ofMinutes(10).toMillis(), -Duration.ofMinutes(10).toMillis()
        }) {
            var feed = new Harness();
            long from = feed.now()
                    - Duration.ofDays(3).toMillis()
                    + Duration.ofMinutes(1).toMillis();
            feed.producers.setProducerRecoveryFromTimestamp(PRE, from);
            feed.open(1, MessageInterest.ALL);
            feed.start();
            feed.clock.advance(Duration.ofHours(1));
            // the producer's clock behind the SDK's by the offset, or ahead of it
            feed.machine.alive(PRE, feed.now() - offset, feed.now(), true);
            assertThat(feed.lastSnapshot(PRE).after())
                    .as("with an offset of " + offset + " ms")
                    .isEqualTo(Instant.ofEpochMilli(
                            feed.now() - offset - Duration.ofDays(3).toMillis()));
        }
    }

    @Test
    void aSessionThatProcessedNothingKeepsItsInitialBoundaryForALaterLoss() {
        var settings = Harness.settings();
        var feed = new Harness(new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
                settings.snapshotCompleteTimeout(),
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
                settings.tick()));
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        // the low-priority session takes no snapshot completes, so nothing moves its checkpoint
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        long boundary = feed.now() - Duration.ofMinutes(30).toMillis();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.clock.advance(Duration.ofHours(1));
        feed.machine.channelLost(2);
        feed.machine.channelReopened(2);
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(2);
        assertThat(feed.lastSnapshot(PRE).after())
                .as("from where its first recovery started, not 30 minutes before the loss")
                .isEqualTo(Instant.ofEpochMilli(boundary));
    }

    @Test
    void aSessionOpenedAfterStartThatProcessedNothingKeepsItsInitialBoundaryForALaterLoss() {
        var settings = Harness.settings();
        var feed = new Harness(new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
                settings.snapshotCompleteTimeout(),
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
                settings.tick()));
        feed.start();
        feed.clock.advance(Duration.ofMinutes(5));
        // opened after start, before any alive, and taking no snapshot completes
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        long boundary = feed.now() - Duration.ofMinutes(30).toMillis();
        feed.alive(PRE);
        feed.accept(feed.lastSnapshot(PRE));
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        feed.clock.advance(Duration.ofHours(1));
        feed.machine.channelLost(2);
        feed.machine.channelReopened(2);
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(2);
        assertThat(feed.lastSnapshot(PRE).after())
                .as("from where it started when it opened, not 30 minutes before the loss")
                .isEqualTo(Instant.ofEpochMilli(boundary));
    }

    @Test
    void aColdStartWithAnInitialSnapshotIntervalAsksThatFarBack() {
        var settings = Harness.settings();
        var withInterval = new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
                settings.snapshotCompleteTimeout(),
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
        feed.start();
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
        feed.start();
        feed.alive(LIVE);
        assertThat(feed.snapshots(LIVE))
                .as("no session takes the live producer")
                .isEmpty();
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).hasSize(1);
    }

    @Test
    void nothingIsAskedForBeforeTheTransportHasBoundEverySessionsChannel()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.machine.start();
        feed.alive(PRE);
        feed.advance(Duration.ofSeconds(5));
        feed.alive(PRE);
        assertThat(feed.calls).as("before the transport's first up").isEmpty();
        assertThat(done(feed.recoverEvent(LIVE)))
                .as("an event recovery before it")
                .isNull();

        // the transport tells up once every session's channel is open
        feed.machine.connectionUp();
        assertThat(feed.snapshots(PRE)).as("at once, its alive being recent").hasSize(1);
        assertThat(feed.snapshots(LIVE)).as("at its first alive").isEmpty();
        feed.alive(LIVE);
        assertThat(feed.snapshots(LIVE)).hasSize(1);
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.counters.failed()).isZero();
    }

    // ---- completion per session

    @Test
    void aProducerIsUpOnceEverySessionThatReceivesItHasSeenItsSnapshotComplete() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.open(3, MessageInterest.HI_PRIORITY_ONLY);
        // next to a high-priority session the low-priority one takes no snapshot completes
        feed.open(new SessionInfo(4, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
        feed.bothUp(1);
        long requestedAt = feed.now();
        assertThat(feed.machine.checkpoint(1, PRE))
                .as("having seen the snapshot complete, it has everything up to the request")
                .isEqualTo(requestedAt);

        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 10_000, t, 0);
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(t - 10_000);
        feed.machine.processed(1, PRE, t - 50_000, t, 0);
        assertThat(feed.machine.checkpoint(1, PRE)).as("an older message").isEqualTo(t - 10_000);
        feed.machine.processed(1, PRE, t, t, feed.lastSnapshot(PRE).requestId());
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
        feed.start();
        feed.bothUp(1, 2, 3);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 10_000, t, 0);
        feed.machine.processed(2, PRE, t - 40_000, t, 0);
        feed.machine.processed(1, LIVE, t - 5_000, t, 0);
        feed.machine.processed(3, LIVE, t - 2_000, t, 0);

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
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot first = feed.lastSnapshot(PRE);
        feed.accept(first);
        // live messages and alives go on while the snapshot comes, and some of it arrives
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 1_000, t, first.requestId());
        feed.machine.processed(1, PRE, t, t, 0);
        feed.sessionAlive(1, PRE);
        assertThat(feed.machine.checkpoint(1, PRE)).isEqualTo(t);

        // the rest never comes: past the deadline for its snapshot complete it is asked for again
        feed.runWithAlives(Duration.ofMinutes(5).plusSeconds(6));
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(first.requestId());
        assertThat(feed.counters.timedOut()).isEqualTo(1);
        assertThat(again.after()).isEqualTo(first.after()).isEqualTo(Instant.ofEpochMilli(from));
    }

    // ---- a producer that stops sending

    @Test
    void anUnsubscribedAliveRecoversFromTheLastSubscribedOneEvenWithSessionsBehind() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        // before the producer took the request: it may not have seen it yet
        feed.unsubscribed(PRE);
        feed.alive(PRE);
        feed.accept(recovery);
        // another session's gap: the producer still has what the recovery is sending
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
    void anUnsubscribedAliveBeforeTheProducerTookTheRecoveryJoinsItAndAsksForOneMoreAfterIt() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        // sent before the API had answered: the producer may not have seen the request yet
        feed.unsubscribed(PRE);
        feed.accept(recovery);
        assertThat(feed.snapshots(PRE)).hasSize(2);
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.snapshots(PRE)).as("one more").hasSize(3);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @Test
    void anUnsubscribedAliveReceivedBeforeTheApisAnswerAndHandledAfterItJoinsTheRecovery() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        long receivedEarlier = feed.now() + 1_000;
        feed.clock.advance(Duration.ofSeconds(2));
        feed.accept(recovery);
        // alives reach the actor apart from the answers: this one was received before the answer
        feed.machine.alive(PRE, receivedEarlier, receivedEarlier, false);
        assertThat(feed.machine.inFlightRecovery(PRE))
                .as("joined, not given up")
                .isEqualTo(recovery.requestId());
        assertThat(feed.counters.abandoned()).isZero();
        feed.machine.snapshotComplete(1, PRE, recovery.requestId());
        assertThat(feed.snapshots(PRE)).as("one more").hasSize(3);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @Test
    void aProducerThatRestartsDuringARecoveryGivesItUpAndIsAskedAgainAtTheNextAlive() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot lost = feed.lastSnapshot(PRE);
        feed.accept(lost);
        feed.machine.snapshotComplete(1, PRE, lost.requestId());
        long downAt = feed.now();

        // the producer took the request, then restarted: it says unsubscribed again
        feed.clock.advance(Duration.ofSeconds(10));
        feed.unsubscribed(PRE);
        assertThat(feed.machine.inFlightRecovery(PRE)).as("given up").isZero();
        assertThat(feed.counters.abandoned()).isEqualTo(1);
        assertThat(feed.counters.failed()).as("not the request's failure").isZero();
        feed.advance(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).as("not before the next alive").hasSize(2);

        feed.clock.advance(Duration.ofSeconds(5));
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(lost.requestId());
        assertThat(again.after()).as("from the same point").isEqualTo(lost.after());
        // what the lost one would have completed changes nothing
        feed.machine.snapshotComplete(2, PRE, lost.requestId());
        assertThat(feed.counters.unknownCompletions()).isEqualTo(1);
        feed.complete(again, 1, 2);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.now() - downAt).as("up again within seconds").isLessThan(30_000);
    }

    @Test
    void aProducerSilentDuringARecoveryGivesItUpAndIsAskedAgainAtItsNextAlive() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot lost = feed.lastSnapshot(PRE);
        feed.accept(lost);
        for (int second = 0; second < 21; second++) {
            feed.advance(Duration.ofSeconds(1));
        }
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.machine.inFlightRecovery(PRE)).as("given up").isZero();
        assertThat(feed.counters.abandoned()).isEqualTo(1);
        assertThat(feed.counters.failed()).isZero();
        assertThat(feed.snapshots(PRE)).as("not without an alive").hasSize(2);

        feed.alive(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(lost.requestId());
        assertThat(again.after()).isEqualTo(lost.after());
        feed.complete(again, 1);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void aLostConnectionGivesUpTheRecoveryInFlightWhoseSnapshotWentWithTheQueues() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
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
        feed.start();
        feed.bothUp(1, 2);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 1_000, t, 0);
        feed.machine.processed(2, PRE, t - 30_000, t, 0);
        feed.alive(PRE);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot waiting = feed.lastSnapshot(PRE);
        feed.accept(waiting);
        feed.machine.snapshotComplete(1, PRE, waiting.requestId());

        feed.machine.channelLost(2);
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.CHANNEL_LOST);
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).requestId())
                .as("nothing asked for before the new channel is bound")
                .isEqualTo(waiting.requestId());
        feed.machine.channelReopened(2);
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

    @Test
    void aLostChannelHoldsTheRecoveryOfItsSessionsProducersUntilItsNewOneIsBound()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.start();
        feed.bothUp(1, 2);
        int before = feed.calls.size();

        feed.machine.channelLost(2);
        feed.runWithAlives(Duration.ofMinutes(1));
        assertThat(feed.calls)
                .as("nothing for the live producer, whose snapshot would reach no queue of session 2")
                .hasSize(before);
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        assertThat(reply).as("an event recovery waits too").isNotDone();
        assertThat(feed.calls).hasSize(before);
        // the prematch producer is not session 2's: nothing held it, and nothing was missing
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.counters.timedOut()).isZero();

        feed.machine.channelReopened(2);
        assertThat(feed.snapshots(LIVE)).as("at once, the alives being recent").hasSize(2);
        Outbox.Call event = feed.calls.stream()
                .filter(call -> call instanceof Outbox.Call.Event)
                .findFirst()
                .orElseThrow();
        feed.accept(event);
        assertThat(done(reply)).isEqualTo(event.requestId());
        feed.complete(feed.lastSnapshot(LIVE), 1, 2);
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
        // a second report of the same new channel changes nothing
        feed.machine.channelReopened(2);
        assertThat(feed.snapshots(LIVE)).hasSize(2);
    }

    // ---- sessions opening and closing

    @Test
    void aSessionThatOpensStartsFromTheProducersRecoveryPointAndAsksForARecovery() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.clock.advance(Duration.ofMinutes(1));
        long t = feed.now();
        feed.machine.processed(1, PRE, t - 5_000, t, 0);

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
        feed.start();
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
        feed.machine.processed(1, PRE, feed.now(), feed.now(), 0);
        feed.machine.connectionDown();
        feed.machine.connectionUp();
        feed.alive(PRE);
        assertThat(feed.lastSnapshot(PRE).after()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        // a late fact of the closed session changes nothing
        feed.machine.processed(2, PRE, 1, feed.now(), 0);
        feed.machine.snapshotComplete(2, PRE, feed.lastSnapshot(PRE).requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @Test
    void aProducerDownOnlyForAClosedSessionsGapComesBack() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
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
        feed.start();
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
        feed.start();
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
    void aRecoveryWhoseSnapshotCompleteIsLostIsAskedForAgainAfterItsDeadline() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        // the session takes the snapshot, and live messages after it; its snapshot complete never comes
        feed.machine.processed(1, PRE, feed.now(), feed.now(), recovery.requestId());
        feed.runWithAlives(Duration.ofMinutes(5));
        assertThat(feed.snapshots(PRE)).as("at the deadline").hasSize(1);
        assertThat(feed.counters.timedOut()).as("at the deadline").isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);
        assertThat(requireNonNull(
                                requireNonNull(feed.producers.getProducer(PRE)).getRecoveryInfo())
                        .getSuccessful())
                .as("the timed-out one")
                .isFalse();
        feed.runWithAlives(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).as("after the first backoff").hasSize(2);
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(feed.producers.isProducerDown(PRE)).as("up within minutes").isFalse();
    }

    @Test
    void aSnapshotStillOnItsWayPutsOffTheDeadlineForItsSnapshotComplete() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.PREMATCH_ONLY, false));
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        long requestedAt = feed.now();
        // a slow session: first what was queued before the request, then the snapshot
        for (int minute = 1; minute <= 6; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, requestedAt - 1_000, feed.now(), 0);
        }
        for (int minute = 1; minute <= 4; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), recovery.requestId());
            // a session that takes no snapshot completes is not waited for, nor heard
            feed.machine.processed(2, PRE, feed.now(), feed.now(), recovery.requestId());
        }
        assertThat(feed.counters.timedOut()).as("ten minutes in, still coming").isZero();
        feed.runWithAlives(Duration.ofMinutes(5));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the last of it")
                .isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);

        // only what the awaited sessions take puts it off
        int asked = feed.snapshots(PRE).size();
        feed.runWithAlives(Duration.ofSeconds(5));
        assertThat(feed.snapshots(PRE)).hasSize(asked + 1);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        for (int minute = 1; minute <= 5; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(2, PRE, feed.now(), feed.now(), again.requestId());
        }
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(2);
    }

    @Test
    void anAliveSentBeforeTheRequestPutsOffTheDeadlineForItsSnapshotComplete() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.accept(feed.lastSnapshot(PRE));
        long requestedAt = feed.now();
        // a slow session takes nothing but alives from before the request
        for (int minute = 1; minute <= 6; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.sessionAlive(1, PRE, requestedAt - 1_000, feed.now(), true);
        }
        assertThat(feed.counters.timedOut()).as("six minutes in, still coming").isZero();
        feed.runWithAlives(Duration.ofMinutes(5));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the last of it")
                .isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);
    }

    @Test
    void theSnapshotOfARecoveryGivenUpPutsOffTheDeadlineOfTheNextOne() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot lost = feed.lastSnapshot(PRE);
        feed.accept(lost);
        feed.clock.advance(Duration.ofSeconds(10));
        feed.unsubscribed(PRE);
        assertThat(feed.counters.abandoned()).isEqualTo(1);
        feed.clock.advance(Duration.ofSeconds(10));
        feed.alive(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(lost.requestId());
        feed.accept(again);
        // a slow session takes what the one given up had sent, which the new one's queues behind
        for (int minute = 1; minute <= 6; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), lost.requestId());
        }
        assertThat(feed.counters.timedOut()).as("six minutes in, still coming").isZero();
        feed.runWithAlives(Duration.ofMinutes(5));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the last of it")
                .isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);
    }

    @Test
    void onlyTheLastEightRecoveriesThatEndedPutOffTheDeadline() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        var ended = new ArrayList<Long>();
        // the producer restarts nine times, each time after it took the recovery
        for (int restart = 1; restart <= 9; restart++) {
            feed.clock.advance(Duration.ofSeconds(10));
            feed.unsubscribed(PRE);
            Outbox.Call.Snapshot lost = feed.lastSnapshot(PRE);
            feed.accept(lost);
            feed.clock.advance(Duration.ofSeconds(10));
            feed.unsubscribed(PRE);
            ended.add(lost.requestId());
        }
        assertThat(feed.counters.abandoned()).isEqualTo(9);
        feed.alive(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(ended).doesNotContain(again.requestId());
        feed.accept(again);
        // what the first of them sent no longer counts
        for (int minute = 1; minute <= 5; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), ended.getFirst());
        }
        assertThat(feed.counters.timedOut()).as("at the deadline").isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the request")
                .isEqualTo(1);
    }

    @Test
    void eventRecoveriesAskedForSinceDoNotPutOffTheDeadlineForItsSnapshotComplete() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.accept(feed.lastSnapshot(PRE));
        // its snapshot complete is lost, while an event recovery a minute sends its snapshot
        for (int minute = 1; minute <= 5; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            assertThat(feed.recoverEvent(PRE)).isNotDone();
            var event = (Outbox.Call.Event) feed.calls.getLast();
            feed.accept(event);
            feed.machine.processed(1, PRE, feed.now(), feed.now(), event.requestId());
            feed.machine.snapshotComplete(1, PRE, event.requestId());
        }
        assertThat(feed.counters.timedOut()).as("at the deadline").isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the request")
                .isEqualTo(1);
    }

    @Test
    void anEventRecoveryAskedForBeforeTheRecoveryIsAheadOfItsSnapshotAndPutsOffTheDeadline() {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        feed.complete(feed.lastSnapshot(PRE), 1);
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        var event = (Outbox.Call.Event) feed.calls.getLast();
        feed.accept(event);
        feed.clock.advance(Duration.ofSeconds(1));
        feed.unsubscribed(PRE);
        feed.accept(feed.lastSnapshot(PRE));
        // a slow session takes the event recovery's snapshot, which the recovery's queues behind
        for (int minute = 1; minute <= 6; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), event.requestId());
        }
        assertThat(feed.counters.timedOut()).as("six minutes in, still coming").isZero();
        feed.runWithAlives(Duration.ofMinutes(5));
        assertThat(feed.counters.timedOut())
                .as("five minutes after the last of it")
                .isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);
    }

    @Test
    void aRecoveryStillOnItsWayAtTheMaximumRecoveryTimeTimesOut() {
        var settings = Harness.settings();
        var feed = new Harness(new RecoverySettings(
                settings.maxInactivity(),
                Duration.ofMinutes(30),
                settings.snapshotCompleteTimeout(),
                settings.initialSnapshotInterval(),
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
                settings.tick()));
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        for (int minute = 1; minute <= 30; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), recovery.requestId());
        }
        assertThat(feed.counters.timedOut()).as("at the maximum recovery time").isZero();
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(1);

        // asked for again after the backoff, behind what the timed-out one still sends
        feed.runWithAlives(Duration.ofSeconds(5));
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.requestId()).isNotEqualTo(recovery.requestId());
        feed.accept(again);
        for (int minute = 1; minute <= 6; minute++) {
            feed.runWithAlives(Duration.ofMinutes(1));
            feed.machine.processed(1, PRE, feed.now(), feed.now(), recovery.requestId());
        }
        assertThat(feed.counters.timedOut()).as("the next one, still behind it").isEqualTo(1);
        feed.runWithAlives(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(feed.counters.timedOut()).isEqualTo(2);
    }

    // ---- what the client hears

    @Test
    void anAliveSayingAProducerStillDownIsUnsubscribedRaisesOnlyTheCauseEvent() {
        // KD-2, as in 0.0.x: down, and for the same public reason, so 0.0.x's callback hears nothing
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        feed.alive(PRE);
        assertThat(feed.statuses).as("alives change no status").isEmpty();
        feed.unsubscribed(PRE);
        assertThat(feed.publicStatuses).as("events for 0.0.x's callback").isEmpty();
        assertThat(feed.statuses).extracting(ProducerStatusChange::cause).containsExactly(StatusCause.UNSUBSCRIBED);
        feed.unsubscribed(PRE);
        assertThat(feed.statuses).as("the same cause again").hasSize(1);
        assertThat(feed.snapshots(PRE))
                .as("the recovery still asked for, and joined")
                .hasSize(1);
    }

    @Test
    void causesSharingAPublicReasonRaiseOnePublicEventAndACauseEventEach() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int publicBefore = feed.publicStatuses.size();
        int causesBefore = feed.statuses.size();
        feed.unsubscribed(PRE);
        feed.machine.channelLost(1);
        assertThat(feed.publicStatuses.subList(publicBefore, feed.publicStatuses.size()))
                .as("down, for OTHER, once")
                .filteredOn(change -> change.producerId() == PRE)
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.UNSUBSCRIBED);
        assertThat(feed.statuses.subList(causesBefore, feed.statuses.size()))
                .filteredOn(change -> change.producerId() == PRE)
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.UNSUBSCRIBED, StatusCause.CHANNEL_LOST);

        // another public reason: both events
        for (int second = 0; second < 21; second++) {
            feed.advance(Duration.ofSeconds(1));
        }
        assertThat(feed.statuses)
                .filteredOn(change -> change.producerId() == PRE)
                .extracting(ProducerStatusChange::cause)
                .containsExactly(
                        StatusCause.FIRST_RECOVERY_COMPLETED,
                        StatusCause.UNSUBSCRIBED,
                        StatusCause.CHANNEL_LOST,
                        StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.publicStatuses)
                .filteredOn(change -> change.producerId() == PRE)
                .extracting(ProducerStatusChange::reason)
                .containsExactly(
                        ProducerStatusReason.FIRST_RECOVERY_COMPLETED,
                        ProducerStatusReason.OTHER,
                        ProducerStatusReason.ALIVE_INTERVAL_VIOLATION);
    }

    @Test
    void aDisabledProducerIsNotRecoveredAndAnUnknownOneIsCounted() {
        feed.producers.setProducerState(LIVE, false);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(LIVE);
        feed.unsubscribed(LIVE);
        feed.machine.alive(9, feed.now(), feed.now(), true);
        feed.machine.processed(1, 9, feed.now(), feed.now(), 0);
        assertThat(feed.calls).isEmpty();
        assertThat(feed.statuses).isEmpty();
        assertThat(feed.counters.unknownProducers()).isEqualTo(2);
    }

    @Test
    void aSessionThatProcessesLateTakesTheProducerDownWithoutARecoveryUntilItCatchesUp() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
        feed.start();
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
    void aSessionClosingBeforeTheApiAnswersLeavesTheAnswerToDecide() {
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot accepted = feed.lastSnapshot(PRE);
        feed.alive(LIVE);
        Outbox.Call.Snapshot refused = feed.lastSnapshot(LIVE);
        // the only session that takes snapshot completes closes while both requests wait for the API
        feed.close(1);
        assertThat(feed.counters.completed())
                .as("no snapshot complete, no answer yet")
                .isZero();
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();

        feed.accept(accepted);
        assertThat(feed.producers.isProducerDown(PRE))
                .as("accepted, with nothing to wait for")
                .isFalse();
        feed.refuse(refused);
        assertThat(feed.producers.isProducerDown(LIVE)).as("refused").isTrue();
        assertThat(feed.counters.completed()).isEqualTo(1);
        assertThat(feed.counters.failed()).isEqualTo(1);
        assertThat(requireNonNull(
                                requireNonNull(feed.producers.getProducer(LIVE)).getRecoveryInfo())
                        .getSuccessful())
                .isFalse();
    }

    @Test
    void aSessionClosingBeforeTheApiAnswersAnEventRecoveryLeavesTheAnswerToDecide()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.HI_PRIORITY_ONLY);
        feed.open(new SessionInfo(2, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        CompletableFuture<@Nullable Long> accepted = feed.recoverEvent(LIVE);
        Outbox.Call acceptedCall = feed.calls.getLast();
        CompletableFuture<@Nullable Long> refused = feed.recoverEvent(LIVE);
        Outbox.Call refusedCall = feed.calls.getLast();
        feed.close(1);
        assertThat(feed.events).as("no snapshot complete, no answer yet").isEmpty();

        feed.refuse(refusedCall);
        assertThat(done(refused)).isNull();
        assertThat(feed.events).as("refused").isEmpty();
        feed.accept(acceptedCall);
        assertThat(done(accepted)).isEqualTo(acceptedCall.requestId());
        assertThat(feed.events)
                .containsExactly("event recovery " + acceptedCall.requestId() + " of " + MATCH + " completed");
    }

    @Test
    void aSessionThatSawTheSnapshotCompleteAndClosedStillCompletesIt() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        // quicker than the API's answer, as a snapshot complete may be
        feed.machine.snapshotComplete(2, PRE, recovery.requestId());
        feed.close(1);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
    }

    @Test
    void aRecoveryThatFailsWithNothingLeftToRecoverBringsTheProducerUp() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        // the session it was for closes while it is in flight, then it is refused
        feed.close(2);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        feed.refuse(recovery);
        assertThat(feed.producers.isProducerDown(PRE))
                .as("nothing left missing")
                .isFalse();
    }

    @Test
    void aRecoveryThatCompletesWhileASessionProcessesTheProducerLateKeepsItDownForThat() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.live(1, PRE, Duration.ofSeconds(25));
        feed.advance(Duration.ofMillis(10));
        feed.complete(recovery, 1);
        ProducerStatusChange late = requireNonNull(feed.lastStatus(PRE));
        assertThat(late.down()).as("recovered, but processed late").isTrue();
        assertThat(late.cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.statuses(PRE)).noneMatch(change -> change.cause() == StatusCause.RECOVERY_COMPLETED);

        feed.live(1, PRE, Duration.ofSeconds(1));
        feed.advance(Duration.ofMillis(10));
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.DELAY_STABILIZED);
    }

    @Test
    void anEventRecoveryThatExpiresBeforeTheApiAnswersAnswersItsCallerNull()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        Outbox.Call call = feed.calls.getLast();
        feed.advance(Duration.ofHours(6).plusSeconds(1));
        assertThat(feed.counters.eventExpired()).isEqualTo(1);
        assertThat(done(reply)).isNull();
        // a late answer finds nothing to answer
        feed.accept(call);
        assertThat(done(reply)).isNull();
    }

    @Test
    void anEventRecoverysSnapshotCompleteBeforeTheApisAnswerSettlesItsCallerWithTheId()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        Outbox.Call call = feed.calls.getLast();
        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        assertThat(feed.events).hasSize(1);
        assertThat(done(reply)).as("the snapshot complete says the API took it").isEqualTo(call.requestId());
        // the API's answer, late, and a failure: what the caller heard stands
        feed.refuse(call);
        assertThat(done(reply)).isEqualTo(call.requestId());
        assertThat(feed.counters.eventRefused()).isZero();
        feed.advance(Duration.ofHours(7));
        assertThat(feed.counters.eventExpired()).isZero();
    }

    @Test
    void aProducerIsUpForItsFirstRecoveryTheFirstTimeItComesUpAfterAFollowUp() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot first = feed.lastSnapshot(PRE);
        // the producer stops sending while the first recovery waits for the API
        feed.unsubscribed(PRE);
        feed.accept(first);
        feed.machine.snapshotComplete(1, PRE, first.requestId());
        Outbox.Call.Snapshot followUp = feed.lastSnapshot(PRE);
        assertThat(followUp.requestId()).isNotEqualTo(first.requestId());
        feed.complete(followUp, 1);
        assertThat(feed.statuses(PRE))
                .filteredOn(change -> !change.down())
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.FIRST_RECOVERY_COMPLETED);
    }

    @Test
    void aProducerIsUpForItsFirstRecoveryWhenAClosedSessionBringsItUp() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot first = feed.lastSnapshot(PRE);
        feed.accept(first);
        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.machine.snapshotComplete(1, PRE, first.requestId());
        // what session 2 misses is asked for, refused, and session 2 closes
        feed.refuse(feed.lastSnapshot(PRE));
        feed.close(2);
        ProducerStatusChange up = requireNonNull(feed.lastStatus(PRE));
        assertThat(up.down()).isFalse();
        assertThat(up.cause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);
        assertThat(up.reason()).isEqualTo(ProducerStatusReason.FIRST_RECOVERY_COMPLETED);
    }

    @Test
    void aProducerLateAtItsFirstRecoveryIsUpForItsFirstRecoveryOnceOnTime() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot first = feed.lastSnapshot(PRE);
        feed.live(1, PRE, Duration.ofSeconds(25));
        feed.advance(Duration.ofMillis(10));
        feed.complete(first, 1);
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        feed.live(1, PRE, Duration.ofSeconds(1));
        feed.advance(Duration.ofMillis(10));
        assertThat(feed.statuses(PRE))
                .filteredOn(change -> !change.down())
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.FIRST_RECOVERY_COMPLETED);
    }

    @Test
    void theFirstSessionsSnapshotCompleteSaysTheApiTookARecoveryWhateverItAnswersLater() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        feed.alive(LIVE);
        Outbox.Call.Snapshot refusedLate = feed.lastSnapshot(PRE);
        Outbox.Call.Snapshot acceptedLate = feed.lastSnapshot(LIVE);
        feed.machine.snapshotComplete(1, PRE, refusedLate.requestId());
        feed.machine.snapshotComplete(1, LIVE, acceptedLate.requestId());
        feed.refuse(refusedLate);
        feed.accept(acceptedLate);
        assertThat(feed.counters.failed())
                .as("a refusal after the snapshot complete")
                .isZero();
        assertThat(feed.snapshots(PRE)).as("nothing asked for again").hasSize(1);
        assertThat(requireNonNull(
                                requireNonNull(feed.producers.getProducer(PRE)).getRecoveryInfo())
                        .getSuccessful())
                .isTrue();
        assertThat(feed.producers.isProducerDown(PRE))
                .as("waiting for session 2")
                .isTrue();
        feed.machine.snapshotComplete(2, PRE, refusedLate.requestId());
        feed.machine.snapshotComplete(2, LIVE, acceptedLate.requestId());
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        assertThat(feed.producers.isProducerDown(LIVE)).isFalse();
    }

    @Test
    void theFirstSessionsSnapshotCompleteOfAnEventRecoveryAnswersItsCallerAtOnce()
            throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        CompletableFuture<@Nullable Long> refusedLate = feed.recoverEvent(LIVE);
        Outbox.Call refusedCall = feed.calls.getLast();
        CompletableFuture<@Nullable Long> acceptedLate = feed.recoverEvent(LIVE);
        Outbox.Call acceptedCall = feed.calls.getLast();
        feed.machine.snapshotComplete(1, LIVE, refusedCall.requestId());
        feed.machine.snapshotComplete(1, LIVE, acceptedCall.requestId());
        assertThat(done(refusedLate)).as("before the API's answer").isEqualTo(refusedCall.requestId());
        assertThat(done(acceptedLate)).isEqualTo(acceptedCall.requestId());
        assertThat(feed.events).as("session 2 has yet to see them").isEmpty();

        feed.refuse(refusedCall);
        feed.accept(acceptedCall);
        assertThat(feed.counters.eventRefused()).isZero();
        feed.machine.snapshotComplete(2, LIVE, refusedCall.requestId());
        feed.machine.snapshotComplete(2, LIVE, acceptedCall.requestId());
        assertThat(feed.events)
                .containsExactly(
                        "event recovery " + refusedCall.requestId() + " of " + MATCH + " completed",
                        "event recovery " + acceptedCall.requestId() + " of " + MATCH + " completed");
    }

    @Test
    void aProducerBroughtUpWithNothingMissingHasItsCapReArmed() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.open(2, MessageInterest.PREMATCH_ONLY);
        feed.alive(PRE);
        for (int attempt = 0; attempt < 4; attempt++) {
            feed.refuse(feed.lastSnapshot(PRE));
            feed.runWithAlives(Duration.ofSeconds(20));
        }
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.RECOVERY_FAILED);
        feed.close(2);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();
        int asked = feed.snapshots(PRE).size();
        feed.unsubscribed(PRE);
        assertThat(feed.snapshots(PRE))
                .as("asked for at once, not after the cool-down")
                .hasSize(asked + 1);
    }

    @Test
    void aMaximumInactivityUnderASecondBreaksNoAlive() {
        var feed = new Harness(Harness.settings(Duration.ZERO));
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        feed.clock.advance(Duration.ofSeconds(2));
        feed.alive(PRE);
        assertThat(feed.snapshots(PRE)).as("asked for at the first alive").hasSize(1);
        assertThat(requireNonNull(feed.producers.getProducer(PRE)).getLastMessageTimestamp())
                .as("the second alive handled")
                .isEqualTo(feed.now());
    }

    @Test
    void closingAnswersEveryoneStillWaitingWithNull() throws ExecutionException, InterruptedException {
        feed.open(1, MessageInterest.ALL);
        feed.start();
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
        feed.start();
        feed.alive(PRE);
        feed.alive(LIVE);
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        List<Long> ids = feed.calls.stream().map(Outbox.Call::requestId).toList();
        assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).isBetween(1L, RequestIds.MAX));
    }
}
