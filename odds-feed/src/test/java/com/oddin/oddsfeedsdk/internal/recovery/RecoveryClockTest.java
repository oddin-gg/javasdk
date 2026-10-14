package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Durations run on the monotonic clock, what is published on the wall one. The machine here has a
 * wall clock and a monotonic one the test moves apart, the two starting at different numbers: a
 * step of the wall clock, forward or back, must neither fire a deadline nor hold one back, and
 * the monotonic clock moving past a limit, the wall one standing still, must fire it.
 */
class RecoveryClockTest {

    private static final Duration OLD = Duration.ofMinutes(3);

    private final Harness feed = new Harness(Harness.settings(), true);

    /** The monotonic seconds {@link #monoSecond} has moved, for the alives' pace. */
    private long seconds;

    // ---- inactivity

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aStepOfTheWallClockMovesNoProducerDownAndTheMonotonicOneDoes(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int statuses = feed.statuses.size();
        int asked = feed.calls.size();

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.statuses).as("status changes after the step").hasSize(statuses);
        assertThat(feed.calls).as("requests after the step").hasSize(asked);
        assertThat(feed.producers.isProducerDown(PRE)).isFalse();

        feed.advanceMono(Duration.ofSeconds(20));
        assertThat(feed.producers.isProducerDown(PRE))
                .as("at the maximum inactivity")
                .isFalse();
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.ALIVE_INTERVAL_VIOLATION);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void theInactivityOfAProducerThatNeverSentAnAliveCountsFromTheStartOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.statuses).isEmpty();
        feed.advanceMono(Duration.ofSeconds(20));
        assertThat(feed.statuses).as("at the maximum inactivity").isEmpty();
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(feed.statuses)
                .extracting(ProducerStatusChange::cause)
                .containsExactly(StatusCause.ALIVE_INTERVAL_VIOLATION, StatusCause.ALIVE_INTERVAL_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aSessionsQuietIsMeasuredOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.live(1, PRE, Duration.ZERO);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.lastStatus(PRE))
                .extracting(ProducerStatusChange::cause)
                .isNotEqualTo(StatusCause.PROCESSING_DELAY);
        for (int second = 0; second < 20; second++) {
            monoSecond();
        }
        assertThat(feed.producers.isProducerDown(PRE))
                .as("20 seconds after the last message")
                .isFalse();
        monoSecond();
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
    }

    @ParameterizedTest
    @CsvSource({"-30, 18, true", "30, 25, false"})
    void aStepOfTheWallClockBetweenTwoAlivesLeavesTheAliveIntervalTheOffsetsFreshnessCountsOn(
            long step, long probe, boolean delayed) {
        var feed = new Harness(Harness.settings(Duration.ofSeconds(60)), true);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        // ten seconds between the alives on the monotonic clock, whatever the wall one says
        feed.advanceMono(Duration.ofSeconds(10));
        feed.stepWall(Duration.ofSeconds(step));
        feed.alive(PRE);
        feed.advanceMono(Duration.ofSeconds(probe));
        // 90 s old by the producer's clock, over the maximum inactivity, when its offset is fresh: it is
        // fresh for two alive intervals, which stay the 10 s that were measured
        feed.live(1, PRE, Duration.ofSeconds(90));
        feed.machine.tick();
        assertThat(feed.producers.isProducerDown(PRE))
                .as("down for the session's delay, " + probe + " s after the alive")
                .isEqualTo(delayed);
    }

    // ---- the deadlines of a recovery

    @ParameterizedTest
    @ValueSource(longs = {600, -600})
    void theDeadlineForASnapshotCompleteCountsOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.counters.timedOut()).isZero();
        assertThat(feed.machine.inFlightRecovery(PRE)).isEqualTo(recovery.requestId());
        monoSeconds(300);
        assertThat(feed.counters.timedOut()).as("at the deadline").isZero();
        monoSecond();
        assertThat(feed.counters.timedOut()).isEqualTo(1);
        assertThat(feed.snapshots(PRE))
                .as("not asked for again before its backoff")
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {600, -600})
    void whatAHeardSnapshotPutsOffIsCountedOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        monoSeconds(240);
        feed.machine.processed(1, PRE, feed.now(), feed.feedClock.stamp(), recovery.requestId());

        feed.stepWall(Duration.ofSeconds(step));
        monoSeconds(300);
        assertThat(feed.counters.timedOut())
                .as("five minutes after the last of the snapshot")
                .isZero();
        monoSecond();
        assertThat(feed.counters.timedOut()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {600, -600})
    void theMaximumRecoveryTimeCountsOnTheMonotonicClock(long step) {
        var feed = new Harness(Harness.settings(Duration.ofMinutes(10), Duration.ofHours(1)), true);
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.alive(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.counters.timedOut()).isZero();
        for (int second = 1; second <= 600; second++) {
            monoSecond(feed);
        }
        assertThat(feed.counters.timedOut()).as("at the maximum").isZero();
        monoSecond(feed);
        assertThat(feed.counters.timedOut()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aFailedRecoveryIsAskedForAgainAfterItsBackoffOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        feed.refuse(feed.lastSnapshot(PRE));
        int asked = feed.snapshots(PRE).size();

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.snapshots(PRE)).as("after the step").hasSize(asked);
        feed.advanceMono(Duration.ofSeconds(4));
        assertThat(feed.snapshots(PRE)).as("before the first backoff of 5 s").hasSize(asked);
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(feed.snapshots(PRE)).as("after it").hasSize(asked + 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {660, -660})
    void aSpentCapIsReArmedAfterTheCooldownOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.alive(PRE);
        for (long pause : new long[] {5, 10, 20}) {
            feed.refuse(feed.lastSnapshot(PRE));
            feed.runWithAlives(Duration.ofSeconds(pause));
        }
        feed.refuse(feed.lastSnapshot(PRE));
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.RECOVERY_FAILED);
        assertThat(feed.snapshots(PRE)).hasSize(4);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.snapshots(PRE)).as("after the step").hasSize(4);
        feed.runWithAlives(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(feed.snapshots(PRE)).as("during the cool-down").hasSize(4);
        feed.runWithAlives(Duration.ofSeconds(1));
        assertThat(feed.snapshots(PRE)).as("re-armed by the cool-down").hasSize(5);
    }

    // ---- event recoveries

    @ParameterizedTest
    @ValueSource(longs = {600, -600})
    void theSilenceDeadlineOfAnEventRecoveryCountsOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        feed.accept(feed.calls.getLast());

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.counters.eventExpired()).isZero();
        feed.advanceMono(feed.settings.snapshotCompleteTimeout());
        assertThat(feed.counters.eventExpired()).as("at the deadline").isZero();
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(feed.counters.eventExpired()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {7_200, -7_200})
    void theMaximumRecoveryTimeOfAnEventRecoveryCountsOnTheMonotonicClock(long step) {
        var feed = new Harness(Harness.settings(Duration.ofMinutes(10), Duration.ofHours(1)), true);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(reply).isNotDone();
        feed.advanceMono(Duration.ofMinutes(10));
        assertThat(reply).as("at the maximum").isNotDone();
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(reply).isDone();
        assertThat(feed.counters.eventExpired()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {7_200, -7_200})
    void aDeferredEventRecoveryExpiresOnTheMonotonicClock(long step) {
        var feed = new Harness(Harness.settings(Duration.ofMinutes(10), Duration.ofHours(1)), true);
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.machine.channelLost(1);
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        assertThat(reply).isNotDone();

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(reply).isNotDone();
        feed.advanceMono(Duration.ofMinutes(10));
        assertThat(reply).as("at the maximum").isNotDone();
        feed.advanceMono(Duration.ofSeconds(1));
        assertThat(reply).isDone();
        assertThat(feed.counters.eventExpired()).isEqualTo(1);
    }

    @Test
    void theSnapshotOfAnEventRecoveryAskedForEarlierIsAheadOfOneAskedForAfterItWhateverTheWallClockDid() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        var earlier = feed.calls.getLast();
        feed.accept(earlier);
        feed.advanceMono(Duration.ofSeconds(1));
        // the wall clock stepped back between the two: the later one is stamped earlier on it
        feed.stepWall(Duration.ofSeconds(-30));
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        var later = feed.calls.getLast();
        feed.accept(later);
        feed.advanceMono(Duration.ofMinutes(4));
        feed.machine.processed(1, LIVE, feed.now(), feed.feedClock.stamp(), earlier.requestId());
        feed.advanceMono(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(feed.counters.eventExpired())
                .as("five minutes after the later one was accepted: it is behind the snapshot that came")
                .isZero();
        feed.advanceMono(Duration.ofMinutes(4));
        assertThat(feed.counters.eventExpired()).as("five minutes after it").isEqualTo(2);
    }

    // ---- the safety net

    @ParameterizedTest
    @ValueSource(longs = {90, -90})
    void theWindowOfStaleMessagesCountsOnTheMonotonicClock(long step) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        int before = feed.calls.size();
        assertThat(stale(10, () -> feed.calls.size() > before)).isEqualTo(-1);

        feed.stepWall(Duration.ofSeconds(step));
        assertThat(feed.calls).as("after the step").hasSize(before);
        assertThat(stale(100, () -> feed.calls.size() > before))
                .as("seconds on to the net's request: 61 from the first stale message")
                .isEqualTo(51);
        assertThat(feed.resets).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, -1})
    void theNetsBackoffAndCapCountOnTheMonotonicClock(long direction) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        long start = feed.feedClock.monoMillis();
        for (int round = 1; round <= 2; round++) {
            int before = feed.calls.size();
            assertThat(stale(300, () -> feed.calls.size() > before))
                    .as("seconds to the net's request in round " + round)
                    .isEqualTo(61);
            resetAndRecover();
        }

        // the third reset waits for the backoff of two minutes, not the window of 61 s
        feed.stepWall(Duration.ofSeconds(300 * direction));
        int before = feed.calls.size();
        assertThat(stale(300, () -> feed.calls.size() > before))
                .as("seconds to the net's request in round 3")
                .isEqualTo(120);
        resetAndRecover();

        // three resets spend the cap: the session lags, whatever the wall clock says of the cool-down
        feed.stepWall(Duration.ofMinutes(20 * direction));
        int spent = feed.calls.size();
        assertThat(stale(300, () -> feed.machine.lagging(1))).isEqualTo(61);
        assertThat(feed.calls).as("no more requests with the cap spent").hasSize(spent);
        assertThat(stale(1_000, () -> feed.calls.size() > spent)).isPositive();
        assertThat((feed.feedClock.monoMillis() - start) / 1_000)
                .as("seconds to the next try: the first reset ages out of the cool-down")
                .isEqualTo(661);
    }

    @Test
    void aSampleTakenBeforeTheCompletionOnTheMonotonicClockIsTheRecoverysWhateverItsWallReadingSays() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1, 2);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        int before = feed.calls.size();
        feed.complete(recovery, 1, 2);
        long wall = feed.now();
        long mono = feed.feedClock.monoMillis();
        // taken a second before the completion, though the wall clock, set back since, has it after
        feed.machine.processed(2, PRE, wall - OLD.toMillis() - 1_000, new Stamp(wall + 5_000, mono - 1_000), 0);
        assertThat(stale(2, 300, () -> feed.calls.size() > before))
                .as("seconds from the completion to the net's request: the window starts again")
                .isEqualTo(61);
    }

    // ---- the order of a message and of the facts about a recovery

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anAliveIsJoinedOrGivesTheRecoveryUpByWhenItWasReceivedOnTheMonotonicClock(boolean receivedBeforeTheAnswer) {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        long wall = feed.now();
        long mono = feed.feedClock.monoMillis();
        feed.advanceMono(Duration.ofSeconds(2));
        feed.accept(recovery);

        // its wall reading says the opposite of its monotonic one
        var received = receivedBeforeTheAnswer
                ? new Stamp(wall + 60_000, mono + 1_000)
                : new Stamp(wall - 60_000, mono + 3_000);
        feed.machine.alive(PRE, feed.now(), received, false);
        if (receivedBeforeTheAnswer) {
            assertThat(feed.machine.inFlightRecovery(PRE))
                    .as("joined, not given up")
                    .isEqualTo(recovery.requestId());
            assertThat(feed.counters.abandoned()).isZero();
        } else {
            assertThat(feed.counters.abandoned()).as("given up").isEqualTo(1);
        }
    }

    // ---- what is published

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void whatIsPublishedFollowsTheWallClockWhateverTheMonotonicOneDoes(long step) {
        feed.open(1, MessageInterest.PREMATCH_ONLY);
        feed.start();
        feed.stepWall(Duration.ofSeconds(step));
        long wall = feed.now();
        feed.alive(PRE);
        Producer pre = requireNonNull(feed.producers.getProducer(PRE));
        assertThat(pre.getLastMessageTimestamp())
                .as("the last message's timestamp")
                .isEqualTo(wall);
        Outbox.Call.Snapshot recovery = feed.lastSnapshot(PRE);
        feed.accept(recovery);
        assertThat(requireNonNull(pre.getRecoveryInfo()).getTimestamp())
                .as("when the recovery was asked for")
                .isEqualTo(wall);
        feed.complete(recovery, 1);
        assertThat(requireNonNull(feed.lastStatus(PRE)).timestamp())
                .as("when the producer came up")
                .isEqualTo(wall);

        // on from there the wall clock only moves forward: a producer's last message timestamp never goes back
        feed.stepWall(Duration.ofSeconds(Math.abs(step)));
        long later = feed.now();
        feed.live(1, PRE, Duration.ZERO);
        assertThat(pre.getLastMessageTimestamp()).isEqualTo(later);
        assertThat(feed.timestampForRecovery(PRE)).as("the resume point").isEqualTo(Instant.ofEpochMilli(later));

        feed.stepWall(Duration.ofSeconds(Math.abs(step)));
        feed.unsubscribed(PRE);
        Outbox.Call.Snapshot again = feed.lastSnapshot(PRE);
        assertThat(again.after())
                .as("the next recovery starts from the last subscribed alive")
                .isEqualTo(Instant.ofEpochMilli(wall));
        feed.accept(again);
        assertThat(requireNonNull(pre.getRecoveryInfo()).getAfter()).isEqualTo(wall);
        assertThat(pre.getRecoveryInfo().getTimestamp()).isEqualTo(feed.now());
        assertThat(requireNonNull(feed.lastStatus(PRE)).timestamp())
                .as("when it went down")
                .isEqualTo(feed.now());
    }

    // ---- helpers

    private void monoSecond() {
        monoSecond(feed);
    }

    /** One second of the monotonic clock alone, ticked, with both producers' alives every ten. */
    private void monoSecond(Harness feed) {
        feed.advanceMono(Duration.ofSeconds(1));
        if (++seconds % 10 == 0) {
            feed.alive(PRE);
            feed.alive(LIVE);
        }
    }

    private void monoSeconds(int count) {
        for (int second = 0; second < count; second++) {
            monoSecond();
        }
    }

    /**
     * Second by second, on the monotonic clock alone, session 1 takes a live message of producer 1
     * that is old by its own clock, with the alives every ten seconds, until {@code until} holds.
     *
     * @return the seconds that took, or -1 when it did not hold within {@code limit}
     */
    private long stale(long limit, BooleanSupplier until) {
        return stale(1, limit, until);
    }

    private long stale(int session, long limit, BooleanSupplier until) {
        for (long second = 1; second <= limit; second++) {
            monoSecond();
            feed.machine.processed(session, PRE, feed.now() - OLD.toMillis(), feed.feedClock.stamp(), 0);
            feed.machine.tick();
            if (until.getAsBoolean()) {
                return second;
            }
        }
        return -1;
    }

    /** The net's reset made, and what it asked for recovered by the session. */
    private void resetAndRecover() {
        feed.accept(feed.lastSnapshot(PRE));
        feed.accept(feed.lastSnapshot(LIVE));
        feed.resetDone();
        feed.complete(feed.lastSnapshot(PRE), 1);
        feed.complete(feed.lastSnapshot(LIVE), 1);
    }
}
