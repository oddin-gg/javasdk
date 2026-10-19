package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus.State.COMPLETED;
import static com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus.State.FAILED;
import static com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus.State.PENDING;
import static com.oddin.oddsfeedsdk.internal.recovery.EventRecoveryStatus.State.TIMED_OUT;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.MATCH;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.START;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An event recovery's status by its request id, as the Go SDK keeps it: pending from the request,
 * then completed, failed or timed out, and forgotten five minutes after that; at most so many ended
 * ones are kept.
 */
class EventRecoveryStatusTest {

    private final Harness feed = new Harness();

    // ---- the transitions

    @Test
    void anEventRecoveryIsPendingFromItsRequestAndCompletedOnceEverySessionSawItsSnapshotComplete() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.LIVE_ONLY);
        feed.start();
        long askedAt = feed.now();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        EventRecoveryStatus pending = status(call);
        assertThat(pending.requestId()).isEqualTo(call.requestId());
        assertThat(pending.producerId()).isEqualTo(LIVE);
        assertThat(pending.eventId()).isEqualTo(MATCH);
        assertThat(pending.state()).isEqualTo(PENDING);
        assertThat(pending.startedAt()).isEqualTo(Instant.ofEpochMilli(askedAt));
        assertThat(pending.endedAt()).isNull();
        assertThat(pending.reason()).isNull();

        feed.clock.advance(Duration.ofSeconds(3));
        feed.accept(call);
        assertThat(status(call).state()).as("accepted").isEqualTo(PENDING);
        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        assertThat(status(call).state()).as("one session of two").isEqualTo(PENDING);
        feed.clock.advance(Duration.ofSeconds(2));
        feed.machine.snapshotComplete(2, LIVE, call.requestId());
        EventRecoveryStatus completed = status(call);
        assertThat(completed.state()).isEqualTo(COMPLETED);
        assertThat(completed.startedAt()).isEqualTo(Instant.ofEpochMilli(askedAt));
        assertThat(completed.endedAt()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        assertThat(completed.reason()).isNull();
    }

    @Test
    void oneNoSessionTakesSnapshotCompletesForCompletesWhenTheApiAcceptsIt() {
        feed.open(new SessionInfo(1, MessageInterest.LOW_PRIORITY_ONLY, false));
        feed.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(PRE);
        assertThat(reply).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        // the caller reads the status as soon as the future says the API took it
        CompletableFuture<EventRecoveryStatus.State> read =
                reply.thenApply(id -> status(call).state());
        feed.accept(call);
        assertThat(read).isCompletedWithValue(COMPLETED);
        assertThat(status(call).state()).isEqualTo(COMPLETED);
    }

    @Test
    void aCallerToldWithTheLastSnapshotCompleteReadsItCompleted() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        CompletableFuture<@Nullable Long> reply = feed.recoverEvent(LIVE);
        Outbox.Call call = feed.calls.getLast();
        CompletableFuture<EventRecoveryStatus.State> read =
                reply.thenApply(id -> status(call).state());
        // the snapshot complete before the API's answer: it says the API took it, and completes it
        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        assertThat(reply).isCompletedWithValue(call.requestId());
        assertThat(read).isCompletedWithValue(COMPLETED);
    }

    @Test
    void aProducerDownForAProcessingDelayLeavesOneInFlightPendingUntilItsSnapshotCompletes() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.bothUp(1);
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.accept(call);
        // the session processes the producer late: down, with no recovery and nothing lost
        feed.live(1, PRE, Duration.ofSeconds(25));
        feed.advance(Duration.ofMillis(10));
        assertThat(requireNonNull(feed.lastStatus(PRE)).cause()).isEqualTo(StatusCause.PROCESSING_DELAY);
        assertThat(feed.producers.isProducerDown(PRE)).isTrue();
        assertThat(status(call).state()).as("its snapshot is still on its way").isEqualTo(PENDING);

        feed.clock.advance(Duration.ofSeconds(1));
        feed.machine.snapshotComplete(1, PRE, call.requestId());
        EventRecoveryStatus completed = status(call);
        assertThat(completed.state()).isEqualTo(COMPLETED);
        assertThat(completed.endedAt()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        assertThat(completed.reason()).isNull();
    }

    @Test
    void oneTheApiRefusedHasFailed() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.refuse(call);
        EventRecoveryStatus failed = status(call);
        assertThat(failed.state()).isEqualTo(FAILED);
        assertThat(failed.endedAt()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        assertThat(failed.reason()).isEqualTo("the API did not accept it: 503");
    }

    @Test
    void oneWhoseSnapshotWentWithTheConnectionHasFailedAnsweredOrNot() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call answered = feed.calls.getLast();
        feed.accept(answered);
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call waiting = feed.calls.getLast();
        feed.machine.connectionDown();
        for (Outbox.Call call : new Outbox.Call[] {answered, waiting}) {
            assertThat(status(call).state()).isEqualTo(FAILED);
            assertThat(status(call).reason()).isEqualTo("its snapshot went with a lost queue");
        }
    }

    @Test
    void aLostChannelFailsOnlyTheOnesItsSessionHadYetToSee() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call seen = feed.calls.getLast();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call unseen = feed.calls.getLast();
        feed.accept(seen);
        feed.accept(unseen);
        feed.machine.snapshotComplete(2, LIVE, seen.requestId());
        feed.machine.channelLost(2);
        assertThat(status(unseen).state()).isEqualTo(FAILED);
        assertThat(status(seen).state()).isEqualTo(PENDING);
        feed.machine.snapshotComplete(1, LIVE, seen.requestId());
        assertThat(status(seen).state()).isEqualTo(COMPLETED);
    }

    @Test
    void aLateFailureAfterASnapshotCompleteChangesNothing() {
        feed.open(1, MessageInterest.ALL);
        feed.open(2, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        // the first snapshot complete says the API took it, whatever it answers later
        feed.machine.snapshotComplete(1, LIVE, call.requestId());
        feed.refuse(call);
        assertThat(status(call).state()).isEqualTo(PENDING);
        feed.machine.snapshotComplete(2, LIVE, call.requestId());
        feed.refuse(call);
        assertThat(status(call).state()).isEqualTo(COMPLETED);
    }

    @Test
    void oneAcceptedWithNothingOfItHeardForTheSnapshotCompleteTimeoutHasTimedOut() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.accept(call);
        feed.advance(feed.settings.snapshotCompleteTimeout());
        assertThat(status(call).state()).as("at the deadline").isEqualTo(PENDING);
        feed.advance(Duration.ofSeconds(1));
        EventRecoveryStatus timedOut = status(call);
        assertThat(timedOut.state()).isEqualTo(TIMED_OUT);
        assertThat(timedOut.endedAt()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        assertThat(timedOut.reason()).isEqualTo("no snapshot complete, nor anything of it heard, within PT5M");
    }

    @Test
    void oneTheApiNeverAnsweredHasTimedOutAtTheMaximumRecoveryTime() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.advance(Duration.ofHours(6));
        assertThat(status(call).state())
                .as("at the maximum recovery time, pending longer than any ended one is kept")
                .isEqualTo(PENDING);
        feed.advance(Duration.ofSeconds(1));
        EventRecoveryStatus timedOut = status(call);
        assertThat(timedOut.state()).isEqualTo(TIMED_OUT);
        assertThat(timedOut.endedAt()).isEqualTo(Instant.ofEpochMilli(feed.now()));
        assertThat(timedOut.reason()).isEqualTo("no snapshot complete within PT6H");
    }

    @Test
    void theOnesInFlightHaveFailedOnceTheFeedCloses() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(LIVE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.accept(call);
        feed.machine.close();
        assertThat(status(call).state()).isEqualTo(FAILED);
        assertThat(status(call).reason()).isEqualTo("the feed closed");
    }

    @Test
    void anIdNeverAskedForHasNoStatusNorDoesARecoveryRefusedBeforeItsRequest() {
        assertThat(feed.recoveryStatuses.get(12_345)).isNull();
        feed.open(1, MessageInterest.ALL);
        feed.start();
        feed.machine.connectionDown();
        assertThat(feed.recoverEvent(LIVE)).isDone();
        assertThat(feed.recoveryStatuses.size())
                .as("refused before any request id")
                .isZero();
        // a producer recovery has a request id, and no status
        feed.machine.connectionUp();
        feed.alive(PRE);
        assertThat(feed.recoveryStatuses.get(feed.lastSnapshot(PRE).requestId()))
                .isNull();
    }

    // ---- what is kept, and for how long

    @Test
    void anEndedStatusIsKeptFiveMinutesThenForgotten() {
        feed.open(1, MessageInterest.ALL);
        feed.start();
        assertThat(feed.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = feed.calls.getLast();
        feed.complete(call, 1);
        feed.advance(Duration.ofMinutes(5));
        assertThat(status(call).state()).as("five minutes after it ended").isEqualTo(COMPLETED);

        feed.clock.advance(Duration.ofMillis(1));
        assertThat(feed.recoveryStatuses.get(call.requestId()))
                .as("past five minutes, before the tick forgets it")
                .isNull();
        assertThat(feed.recoveryStatuses.size()).isEqualTo(1);
        feed.machine.tick();
        assertThat(feed.recoveryStatuses.size()).as("forgotten at the tick").isZero();
    }

    // ---- retention on the monotonic clock

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aStepOfTheWallClockNeitherForgetsAnEndedStatusEarlyNorKeepsItLonger(long minutes) {
        var split = new Harness(Harness.settings(), true);
        split.open(1, MessageInterest.ALL);
        split.start();
        assertThat(split.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = split.calls.getLast();
        split.complete(call, 1);
        long id = call.requestId();
        Instant endedAt =
                requireNonNull(requireNonNull(split.recoveryStatuses.get(id)).endedAt());

        split.stepWall(Duration.ofMinutes(minutes));
        split.advanceMono(Duration.ofMinutes(5));
        assertThat(split.recoveryStatuses.get(id))
                .as("five minutes of the monotonic clock after it ended, whatever the wall clock says")
                .isNotNull();
        assertThat(split.recoveryStatuses.size()).isEqualTo(1);

        split.advanceMono(Duration.ofMillis(1));
        assertThat(split.recoveryStatuses.get(id))
                .as("past five minutes of the monotonic clock")
                .isNull();
        assertThat(split.recoveryStatuses.size()).as("forgotten at the tick").isZero();
        assertThat(endedAt).as("published when it ended, by the wall clock").isEqualTo(START);
    }

    @Test
    void theInstantsAStatusPublishesAreWallTimeAndAStepOfTheWallClockDoesNotChangeThem() {
        var split = new Harness(Harness.settings(), true);
        split.open(1, MessageInterest.ALL);
        split.start();
        assertThat(split.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = split.calls.getLast();
        split.stepWall(Duration.ofMinutes(10));
        split.advanceMono(Duration.ofSeconds(3));
        long stepped = split.now();
        split.complete(call, 1);

        EventRecoveryStatus completed = requireNonNull(split.recoveryStatuses.get(call.requestId()));
        assertThat(completed.startedAt()).isEqualTo(START);
        assertThat(completed.endedAt())
                .as("by the wall clock, not the monotonic one")
                .isEqualTo(Instant.ofEpochMilli(stepped));

        split.stepWall(Duration.ofMinutes(-30));
        EventRecoveryStatus later = requireNonNull(split.recoveryStatuses.get(call.requestId()));
        assertThat(later).isEqualTo(completed);
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aStatusTheCloseAbandonedIsKeptFiveMonotonicMinutesFromTheAbandonAndPublishesItsWallTime(long minutes) {
        var split = new Harness(Harness.settings(), true);
        split.open(1, MessageInterest.ALL);
        split.start();
        assertThat(split.recoverEvent(PRE)).isNotDone();
        long id = split.calls.getLast().requestId();
        split.stepWall(Duration.ofMinutes(1));
        long abandonedWall = split.now();
        split.recoveryStatuses.abandon();

        split.stepWall(Duration.ofMinutes(minutes));
        split.advanceMono(Duration.ofMinutes(5));
        EventRecoveryStatus failed = requireNonNull(split.recoveryStatuses.get(id));
        assertThat(failed.state()).isEqualTo(FAILED);
        assertThat(failed.reason()).isEqualTo(EventRecoveryStatuses.FEED_CLOSED);
        assertThat(failed.endedAt()).isEqualTo(Instant.ofEpochMilli(abandonedWall));

        split.advanceMono(Duration.ofMillis(1));
        assertThat(split.recoveryStatuses.get(id)).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aRecoveryTheActorEndsAfterTheCloseAbandonedItReadsFailedFromTheAbandonAndIsKeptFromIt(long minutes) {
        var split = new Harness(Harness.settings(), true);
        split.open(1, MessageInterest.ALL);
        split.start();
        assertThat(split.recoverEvent(PRE)).isNotDone();
        Outbox.Call call = split.calls.getLast();
        long id = call.requestId();
        split.stepWall(Duration.ofMinutes(1));
        long abandonedWall = split.now();
        split.recoveryStatuses.abandon();

        // the actor reaches its end later, by the clocks of both sides: it completes
        split.advanceMono(Duration.ofMinutes(2));
        split.stepWall(Duration.ofMinutes(minutes));
        split.complete(call, 1);
        assertThat(split.now()).as("the completion's own wall reading").isNotEqualTo(abandonedWall);
        EventRecoveryStatus failed = requireNonNull(split.recoveryStatuses.get(id));
        assertThat(failed.state()).as("not the end the actor reached").isEqualTo(FAILED);
        assertThat(failed.reason()).isEqualTo(EventRecoveryStatuses.FEED_CLOSED);
        assertThat(failed.endedAt()).as("when the close gave up").isEqualTo(Instant.ofEpochMilli(abandonedWall));
        assertThat(failed.startedAt()).isEqualTo(START);

        split.advanceMono(Duration.ofMinutes(3));
        assertThat(split.recoveryStatuses.get(id))
                .as("five minutes of the monotonic clock after the abandon")
                .isNotNull();
        split.advanceMono(Duration.ofMillis(1));
        assertThat(split.recoveryStatuses.get(id))
                .as("past five minutes from the abandon, not from the completion")
                .isNull();
        assertThat(split.recoveryStatuses.size()).as("forgotten at the tick").isZero();
    }

    @Test
    void aReadThatSawThePendingStatusDoesNotShowItFailedForACloseThatGaveUpAfterItCompleted() {
        var statuses = new ReadHook();
        var recoveries = new EventRecoveryStatuses(feed.feedClock, new RecoveryCounters(), 10, statuses);
        recoveries.pending(1, PRE, MATCH, feed.clock.instant());

        // between the read's look at the map and its look at the close: the actor completes the
        // recovery, then the close gives up waiting for the actor
        statuses.afterNextGet = () -> {
            recoveries.ended(1, COMPLETED, feed.clock.instant(), null, feed.feedClock.monoMillis());
            recoveries.abandon();
        };
        EventRecoveryStatus first = requireNonNull(recoveries.get(1));
        assertThat(statuses.afterNextGet).as("the interleaving happened").isNull();
        EventRecoveryStatus next = requireNonNull(recoveries.get(1));

        assertThat(next.state()).as("the next read").isEqualTo(COMPLETED);
        assertThat(first.state())
                .as("a read that is failed is never followed by one that is completed")
                .isEqualTo(PENDING);
    }

    /** The statuses' map, running a test's step once, right after the next read of it. */
    @SuppressWarnings("serial")
    private static final class ReadHook extends ConcurrentHashMap<Long, EventRecoveryStatuses.Kept> {

        @Nullable
        Runnable afterNextGet;

        @Override
        public EventRecoveryStatuses.@Nullable Kept get(Object key) {
            var kept = super.get(key);
            var step = afterNextGet;
            if (step != null) {
                afterNextGet = null;
                step.run();
            }
            return kept;
        }
    }

    @Test
    void pastTheBoundTheStatusThatEndedFirstIsForgottenAndCounted() {
        var counters = new RecoveryCounters();
        var statuses = new EventRecoveryStatuses(feed.feedClock, counters, 3);
        for (long id = 1; id <= 5; id++) {
            statuses.pending(id, PRE, MATCH, feed.clock.instant());
        }
        assertThat(statuses.size()).as("pending ones are never dropped").isEqualTo(5);
        for (long id = 1; id <= 4; id++) {
            statuses.ended(id, COMPLETED, feed.clock.instant(), null, feed.feedClock.monoMillis());
        }
        assertThat(statuses.get(1)).as("the first to end").isNull();
        assertThat(requireNonNull(statuses.get(2)).state()).isEqualTo(COMPLETED);
        assertThat(requireNonNull(statuses.get(5)).state()).isEqualTo(PENDING);
        assertThat(statuses.size()).isEqualTo(4);
        assertThat(counters.eventStatusesDropped()).isEqualTo(1);
    }

    @Test
    void theActorsBoundIsTenThousandEndedStatuses() {
        var counters = new RecoveryCounters();
        var statuses = new EventRecoveryStatuses(feed.feedClock, counters);
        for (long id = 1; id <= EventRecoveryStatuses.ENDED_KEPT + 1; id++) {
            statuses.pending(id, PRE, MATCH, feed.clock.instant());
            statuses.ended(id, FAILED, feed.clock.instant(), "refused", feed.feedClock.monoMillis());
        }
        assertThat(EventRecoveryStatuses.ENDED_KEPT).isEqualTo(10_000);
        assertThat(statuses.get(1)).isNull();
        assertThat(statuses.get(2)).isNotNull();
        assertThat(counters.eventStatusesDropped()).isEqualTo(1);
    }

    @Test
    void anEndedStatusEndsOnceAndAnIdAskedForAgainIsNotForgottenWithTheOldOne() {
        var statuses = new EventRecoveryStatuses(feed.feedClock, new RecoveryCounters());
        URN other = URN.parse("od:match:2");
        statuses.pending(1, PRE, MATCH, feed.clock.instant());
        statuses.ended(1, COMPLETED, feed.clock.instant(), null, feed.feedClock.monoMillis());
        statuses.ended(1, FAILED, feed.clock.instant(), "late", feed.feedClock.monoMillis());
        assertThat(requireNonNull(statuses.get(1)).state()).as("ended once").isEqualTo(COMPLETED);
        statuses.ended(2, FAILED, feed.clock.instant(), "never asked for", feed.feedClock.monoMillis());
        assertThat(statuses.get(2)).isNull();

        // the id asked for again, within the five minutes of the first
        feed.clock.advance(Duration.ofMinutes(1));
        statuses.pending(1, LIVE, other, feed.clock.instant());
        feed.clock.advance(Duration.ofMinutes(5));
        statuses.expire(feed.feedClock.monoMillis());
        EventRecoveryStatus again = requireNonNull(statuses.get(1));
        assertThat(again.eventId()).isEqualTo(other);
        assertThat(again.state()).isEqualTo(PENDING);
    }

    private EventRecoveryStatus status(Outbox.Call call) {
        @Nullable EventRecoveryStatus status = feed.recoveryStatuses.get(call.requestId());
        return requireNonNull(status, "a status for request " + call.requestId());
    }
}
