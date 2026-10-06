package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.MATCH;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.amqp.Queues;
import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.amqp.SessionQueue;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.internal.rest.RecoveryRequests;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The actor around the state machine, on its own thread with real workers: facts posted from other
 * threads reach it in order, requests run on the workers and their answers come back, a full queue
 * drops and counts instead of waiting, and closing answers whoever still waits. The rules
 * themselves are {@link RecoveryMachineTest}'s; here the waits are generous, since real threads run.
 */
class RecoveryActorTest {

    private static final long WAIT_SECONDS = 10;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Api api = new Api();
    private final BlockingQueue<ProducerStatusChange> statuses = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final Transport transport = new Transport();
    private @Nullable RecoveryActor actor;

    @AfterEach
    void close() {
        if (actor != null) {
            actor.close();
        }
        workers.shutdownNow();
    }

    @Test
    void anAliveLeadsToARequestOnAWorkerAndItsSnapshotCompleteToTheProducerUp() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);

        Request request = api.next();
        assertThat(request.producer()).isEqualTo("pre");
        assertThat(request.after()).isNull();
        assertThat(request.thread()).as("the request's thread").isNotEqualTo("oddsfeed-recovery");
        session.snapshotComplete(PRE, request.requestId());

        ProducerStatusChange up = requireNonNull(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(up.cause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);
        assertThat(producers.isProducerDown(PRE)).isFalse();
        assertThat(actor.turnedAt()).isPositive();
    }

    @Test
    void theActorPublishesTheResumePointAsTheFactsMoveIt() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        Instant from = Instant.ofEpochMilli(System.currentTimeMillis()).minus(Duration.ofHours(1));
        producers.setProducerRecoveryFromTimestamp(PRE, from.toEpochMilli());
        Producer held = requireNonNull(producers.getProducer(PRE));
        actor.start();
        actor.up();
        bothUp(actor, session);
        awaitTimestampForRecovery(held, at -> at != null && at.isAfter(from), "past the client's start");

        Instant processed = Instant.ofEpochMilli(System.currentTimeMillis() + 1_000);
        session.processed(PRE, processed.toEpochMilli(), processed.toEpochMilli(), 0);
        awaitTimestampForRecovery(held, processed::equals, "the message processed");

        // an alive on the SDK's alive channel ahead of the session moves nothing
        long later = processed.toEpochMilli() + 1_000;
        actor.alive(PRE, later, later, true);
        awaitIdle(actor);
        assertThat(held.getTimestampForRecovery())
                .as("the session's checkpoint, not the later alive")
                .isEqualTo(processed);
    }

    @Test
    void aTickThatFindsTheProducerSilentTakesThePointBackToItsLastSubscribedAlive() throws InterruptedException {
        RecoveryActor actor = actor(withInactivity(Duration.ofSeconds(2), null));
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long lastSubscribed = System.currentTimeMillis();
        actor.alive(PRE, lastSubscribed, lastSubscribed, true);
        Request first = api.next();
        session.snapshotComplete(PRE, first.requestId());
        awaitUp(PRE);
        Producer held = requireNonNull(producers.getProducer(PRE));
        Instant processed = Instant.ofEpochMilli(lastSubscribed + 1_000);
        session.processed(PRE, processed.toEpochMilli(), processed.toEpochMilli(), 0);
        awaitTimestampForRecovery(held, processed::equals, "the message processed");

        // nothing more is posted: only the actor's ticks see the producer silent
        awaitTimestampForRecovery(
                held,
                Instant.ofEpochMilli(lastSubscribed)::equals,
                "the producer's gap from its last subscribed alive");
    }

    @Test
    void aGapQueuedWhenTheCloseComesTakesThePointBackBeforeTheActorEnds() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long lastSubscribed = System.currentTimeMillis();
        actor.alive(PRE, lastSubscribed, lastSubscribed, true);
        actor.alive(LIVE, lastSubscribed, lastSubscribed, true);
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        Producer held = requireNonNull(producers.getProducer(PRE));
        Instant processed = Instant.ofEpochMilli(lastSubscribed + 1_000);
        session.processed(PRE, processed.toEpochMilli(), processed.toEpochMilli(), 0);
        awaitTimestampForRecovery(held, processed::equals, "the message processed");

        // the actor held in a fact while an unsubscribed alive queues behind it and the close comes
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        actor.beforeHandle = fact -> {
            if (fact instanceof RecoveryActor.Fact.Processed) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        long later = processed.toEpochMilli() + 1_000;
        session.processed(PRE, later, later, 0);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        actor.alive(PRE, later, later, false);
        Thread closer = Thread.ofPlatform().start(actor::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!actor.closeBegun() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.closeBegun()).isTrue();
        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertThat(actor.running()).isFalse();
        assertThat(held.getTimestampForRecovery())
                .as("the producer's gap from its last subscribed alive, handled before the actor ended")
                .isEqualTo(Instant.ofEpochMilli(lastSubscribed));
        assertThat(api.requests.poll(200, TimeUnit.MILLISECONDS))
                .as("nothing asked for while closing")
                .isNull();
    }

    @Test
    void theFeedClosingItsSessionsBeforeTheActorKeepsThePointOfTheOneBehind() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts ahead = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        SessionFacts behind = actor.openSession(new SessionInfo(2, MessageInterest.ALL, true), transport);
        Instant behindAt = twoSessionsApart(actor, ahead, behind);
        Producer held = requireNonNull(producers.getProducer(PRE));

        actor.closing();
        behind.closed();
        ahead.closed();
        awaitIdle(actor);
        actor.close();
        assertThat(held.getTimestampForRecovery())
                .as("the sessions closed first, the one behind among them")
                .isEqualTo(behindAt);
    }

    @Test
    void aSessionsCloseQueuedWhenTheActorClosesMovesThePointNoFurther() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts ahead = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        SessionFacts behind = actor.openSession(new SessionInfo(2, MessageInterest.ALL, true), transport);
        Instant behindAt = twoSessionsApart(actor, ahead, behind);
        Producer held = requireNonNull(producers.getProducer(PRE));

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        actor.beforeHandle = fact -> {
            if (fact instanceof RecoveryActor.Fact.Processed) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        long later = System.currentTimeMillis() + 10_000;
        ahead.processed(PRE, later, later, 0);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        behind.closed();
        Thread closer = Thread.ofPlatform().start(actor::close);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!actor.closeBegun() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        ahead.closed();
        assertThat(actor.running()).isFalse();
        assertThat(held.getTimestampForRecovery())
                .as("the actor closed first, with the close of the one behind queued")
                .isEqualTo(behindAt);
    }

    @Test
    void aSessionClosedWhileTheFeedRunsNoLongerHoldsThePointBack() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts ahead = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        SessionFacts behind = actor.openSession(new SessionInfo(2, MessageInterest.ALL, true), transport);
        twoSessionsApart(actor, ahead, behind);
        Producer held = requireNonNull(producers.getProducer(PRE));
        behind.closed();
        awaitTimestampForRecovery(held, aheadAt::equals, "the session ahead's checkpoint");
    }

    private Instant aheadAt = Instant.EPOCH;

    /**
     * Both producers up on two sessions, then a message of the prematch producer processed on each,
     * the second's ten seconds older: what the point is now, the second's.
     */
    private Instant twoSessionsApart(RecoveryActor actor, SessionFacts first, SessionFacts second)
            throws InterruptedException {
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        for (Request request : List.of(api.next(), api.next())) {
            first.snapshotComplete(producerOf(request), request.requestId());
            second.snapshotComplete(producerOf(request), request.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
        long ahead = now + 20_000;
        long behind = ahead - 10_000;
        first.processed(PRE, ahead, ahead, 0);
        second.processed(PRE, behind, behind, 0);
        aheadAt = Instant.ofEpochMilli(ahead);
        Instant behindAt = Instant.ofEpochMilli(behind);
        awaitTimestampForRecovery(
                requireNonNull(producers.getProducer(PRE)), behindAt::equals, "the session behind's checkpoint");
        return behindAt;
    }

    /** Waits until what the producer reports for recovery is as {@code expected} says. */
    private static void awaitTimestampForRecovery(
            Producer producer, Predicate<@Nullable Instant> expected, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!expected.test(producer.getTimestampForRecovery()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(producer.getTimestampForRecovery()).as(description).matches(expected);
    }

    @Test
    void nothingIsAskedForBeforeTheTransportsFirstUp() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        assertThat(api.requests.poll(200, TimeUnit.MILLISECONDS))
                .as("with the sessions' channels not bound yet")
                .isNull();

        actor.up();
        Request request = api.next();
        assertThat(request.producer()).isEqualTo("pre");
        session.snapshotComplete(PRE, request.requestId());
        awaitUp(PRE);
    }

    @Test
    void aRequestTheApiRefusedIsAskedForAgain() throws InterruptedException {
        RecoveryActor actor =
                actor(settings(Duration.ofMillis(50), Harness.settings().staleWindow()));
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        api.refuse.set(true);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        Request first = api.next();
        Request again = api.next();
        assertThat(again.requestId()).isNotEqualTo(first.requestId());
        assertThat(actor.counters().reissued()).isPositive();
    }

    @Test
    void anEventRecoveryAnswersOnTheCallersFutureAndCompletesOnItsSnapshotComplete()
            throws InterruptedException, ExecutionException, TimeoutException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        Long requestId = actor.recoverEvent(LIVE, MATCH, true).get(WAIT_SECONDS, TimeUnit.SECONDS);
        Request request = api.next();
        assertThat(request.path()).isEqualTo("stateful " + MATCH);
        assertThat(requestId).isEqualTo(request.requestId());
        session.snapshotComplete(LIVE, request.requestId());
        assertThat(events.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("event recovery " + requestId + " completed");
    }

    @Test
    void anEventRecoverysStatusIsReadFromAnotherThreadAsTheActorMovesIt()
            throws InterruptedException, ExecutionException, TimeoutException {
        RecoveryActor actor = actor(settings());
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        Long requestId = requireNonNull(actor.recoverEvent(LIVE, MATCH, false).get(WAIT_SECONDS, TimeUnit.SECONDS));
        EventRecoveryStatus pending = requireNonNull(actor.recoveryStatus(requestId));
        assertThat(pending.state()).isEqualTo(EventRecoveryStatus.State.PENDING);
        assertThat(pending.eventId()).isEqualTo(MATCH);
        assertThat(actor.recoveryStatus(requestId + 1))
                .as("an id never asked for")
                .isNull();

        session.snapshotComplete(LIVE, requestId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (requireNonNull(actor.recoveryStatus(requestId)).state() == EventRecoveryStatus.State.PENDING
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(requireNonNull(actor.recoveryStatus(requestId)).state())
                .isEqualTo(EventRecoveryStatus.State.COMPLETED);
    }

    @Test
    void theSafetyNetsResetRunsOnAWorkerAndCountsWhatTheQueueHeld() throws InterruptedException {
        RecoveryActor actor = actor(settings(Harness.settings().firstReissueBackoff(), Duration.ZERO));
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        // the two run on workers of their own, so either may come first
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        assertThat(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        // three deliveries the session has not taken, and two samples older than the limit, which
        // the window, nothing, lets act at once
        long later = System.currentTimeMillis();
        session.processed(PRE, later - 300_000, later + 1, 0);
        session.processed(PRE, later - 300_000, later + 2, 0);
        api.next();
        api.next();
        assertThat(transport.resets.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("reset")
                .isTrue();
        assertThat(transport.resetThread).isNotEqualTo("oddsfeed-recovery");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.counters().resets() != 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.counters().resets()).as("counted once reported done").isEqualTo(1);
        while (actor.counters().resetDropped() != 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.counters().resetDropped()).isEqualTo(3);
    }

    @Test
    void aSnapshotCompleteWhileTheResetIsHeldOnItsWorkerIsAskedForAgainOnceTheResetIsDone()
            throws InterruptedException {
        var held = new Held();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), held);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        held.runNext();
        held.runNext();
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);

        // two samples past the limit, with a window of nothing: the net asks for both producers
        long later = System.currentTimeMillis();
        session.processed(PRE, later - 300_000, later + 1, 0);
        session.processed(PRE, later - 300_000, later + 2, 0);
        held.runNext();
        held.runNext();
        List<Request> probes = List.of(api.next(), api.next());
        Runnable reset = held.next();

        // what the session sees before the transport has replaced its channel is the old queue's
        for (Request probe : probes) {
            session.snapshotComplete(producerOf(probe), probe.requestId());
        }
        assertThat(held.tasks.poll(300, TimeUnit.MILLISECONDS))
                .as("work while the reset is held")
                .isNull();
        assertThat(producers.isProducerDown(PRE)).isTrue();
        assertThat(producers.isProducerDown(LIVE)).isTrue();

        reset.run();
        assertThat(transport.resets.getCount()).as("the channel replaced").isZero();
        held.runNext();
        held.runNext();
        List<Request> again = List.of(api.next(), api.next());
        assertThat(again)
                .extracting(Request::requestId)
                .as("asked for again, with new ids")
                .doesNotContainAnyElementsOf(
                        probes.stream().map(Request::requestId).toList());
        for (Request request : again) {
            session.snapshotComplete(producerOf(request), request.requestId());
        }
        // the first two, then the two asked for again; the probes were given up
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.counters().completed() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.counters().completed()).isEqualTo(4);
        assertThat(actor.counters().abandoned()).isEqualTo(2);
    }

    @Test
    void anAnswerReachesTheActorEvenWithItsControlQueueFull()
            throws InterruptedException, ExecutionException, TimeoutException {
        var held = new Held();
        var wedge = new CountDownLatch(1);
        var wedged = new CountDownLatch(1);
        var slow = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                wedged.countDown();
                try {
                    assertThat(wedge.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var actor =
                new RecoveryActor(producers, settings(), api, slow, held, InstantSource.system(), new Random(1), 4, 4);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        CompletableFuture<@Nullable Long> reply = actor.recoverEvent(LIVE, MATCH, false);
        Runnable request = held.next();
        long now = System.currentTimeMillis();
        // a status change the listener holds the actor's thread in, then a full control queue
        actor.alive(PRE, now, now, false);
        assertThat(wedged.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        while (actor.counters().factsDropped() == 0) {
            assertThat(actor.recoverEvent(PRE, MATCH, false)).isNotNull();
        }
        long dropped = actor.counters().factsDropped();

        Thread answering = Thread.ofVirtual().start(request);
        assertThat(answering.join(Duration.ofSeconds(WAIT_SECONDS)))
                .as("the answer handed over")
                .isTrue();
        assertThat(actor.counters().factsDropped())
                .as("the answer not among the dropped")
                .isEqualTo(dropped);
        assertThat(reply).isNotDone();
        wedge.countDown();
        assertThat(reply.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(api.next().requestId());
    }

    @Test
    void whatACallerChainsToItsReplyNeverRunsOnTheActor()
            throws InterruptedException, ExecutionException, TimeoutException {
        var held = new Held();
        var actor = new RecoveryActor(producers, settings(), api, events(), held);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        // a caller that waits, in what it chains to its reply, for the actor to handle a later fact
        var sawUp = new CompletableFuture<Boolean>();
        CompletableFuture<Void> chained = actor.recoverEvent(LIVE, MATCH, false).thenRun(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (producers.isProducerDown(PRE) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            sawUp.complete(!producers.isProducerDown(PRE));
        });
        held.runNext();
        api.next();

        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        held.runNext();
        session.snapshotComplete(PRE, api.next().requestId());
        assertThat(sawUp.get(WAIT_SECONDS * 2, TimeUnit.SECONDS))
                .as("the actor went on while the caller waited")
                .isTrue();
        // what thenRun returned completes just after the chained step itself returns
        chained.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void anErrorFromTheListenerDoesNotStopTheActor() throws InterruptedException {
        var throwing = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                throw new AssertionError("a listener bug");
            }
        };
        // a tick an hour away: only the transition itself asks for the recovery
        var hourly = settings(
                Harness.settings().firstReissueBackoff(), Harness.settings().staleWindow(), Duration.ofHours(1));
        RecoveryActor actor = new RecoveryActor(producers, hourly, api, throwing, workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        // its request shows the actor's first tick is past, and the next is an hour away
        actor.alive(LIVE, now, now, true);
        assertThat(api.next().producer()).isEqualTo("live");
        // a wide margin for the turn that handled it to end, its tick included
        Thread.sleep(200);
        // the status change throws before the recovery is asked for: the transition still finishes
        actor.alive(PRE, now, now, false);
        assertThat(api.next().producer()).as("after the listener threw").isEqualTo("pre");
        assertThat(actor.counters().factsFailed()).isZero();
    }

    @Test
    void anActorWhoseLoopEndsWithAnErrorIsClosedAndTurnsEveryoneAway()
            throws InterruptedException, ExecutionException, TimeoutException {
        var broken = new AtomicBoolean();
        var ended = new CountDownLatch(1);
        InstantSource clock = () -> {
            if (broken.get()) {
                ended.countDown();
                throw new AssertionError("a broken clock");
            }
            return java.time.Instant.now();
        };
        var actor = new RecoveryActor(producers, settings(), api, events(), workers, clock, new Random(1), 10, 10);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        broken.set(true);
        assertThat(ended.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.running() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.running()).as("the actor's thread").isFalse();
        CompletableFuture<@Nullable Long> reply = actor.recoverEvent(PRE, MATCH, false);
        assertThat(reply.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("from an actor that is gone")
                .isNull();
        assertThat(api.requests).as("nothing reached the API").isEmpty();
    }

    @Test
    void aResetThatFailsWithAnErrorBeforeReplacingTheChannelIsReportedNotMade() throws InterruptedException {
        resetThatFails(new AssertionError("a broken channel"));
    }

    @Test
    void aResetThatFailsWithAnExceptionBeforeReplacingTheChannelIsReportedNotMade() throws InterruptedException {
        resetThatFails(new IllegalStateException("a broken channel"));
    }

    @Test
    void aResetThatFailsWithAnErrorAfterTakingTheOldDeliveriesOutIsMade() throws InterruptedException {
        resetThatFailsAfterMoving(new AssertionError("a channel broken after the epoch moved"));
    }

    @Test
    void aResetThatFailsWithAnExceptionAfterTakingTheOldDeliveriesOutIsMade() throws InterruptedException {
        resetThatFailsAfterMoving(new IllegalStateException("a channel broken after the epoch moved"));
    }

    /** The transport moved the epoch, so the old queue is gone, and then failed. */
    private void resetThatFailsAfterMoving(Throwable failure) throws InterruptedException {
        var held = new Held();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), held);
        this.actor = actor;
        transport.failAfterMoving = failure;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        held.runNext();
        held.runNext();
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
        stale(session);
        held.runNext();
        held.runNext();
        api.next();
        api.next();
        Runnable reset = held.next();
        if (failure instanceof Error) {
            assertThatThrownBy(reset::run).isSameAs(failure);
        } else {
            reset.run();
        }
        held.runNext();
        held.runNext();
        assertThat(List.of(api.next(), api.next()))
                .as("asked for again, the queue being lost")
                .hasSize(2);
        assertThat(actor.counters().resets()).as("resets made").isEqualTo(1);
        assertThat(actor.counters().resetDropped()).isEqualTo(3);
        assertThat(events).as("events").contains("reset");
    }

    /** The transport's reset fails before it replaced the channel: nothing was dropped. */
    private void resetThatFails(Throwable failure) throws InterruptedException {
        var held = new Held();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), held);
        this.actor = actor;
        transport.failWith = failure;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        held.runNext();
        held.runNext();
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
        stale(session);
        held.runNext();
        held.runNext();
        api.next();
        api.next();
        Runnable reset = held.next();
        if (failure instanceof Error) {
            assertThatThrownBy(reset::run).isSameAs(failure);
        } else {
            reset.run();
        }
        held.runNext();
        held.runNext();
        assertThat(List.of(api.next(), api.next()))
                .as("what the reset's wait ignored, asked for again")
                .hasSize(2);
        assertThat(actor.counters().resets()).as("resets made").isZero();
        assertThat(actor.counters().resetDropped()).isZero();
        assertThat(events).as("events").doesNotContain("reset");
    }

    @Test
    void aResetWaitingForItsChannelEndsWhenTheActorCloses() throws InterruptedException {
        var held = new Held();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), held);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        held.runNext();
        held.runNext();
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
        transport.reopenLater = true;
        stale(session);
        held.runNext();
        held.runNext();
        api.next();
        api.next();
        Thread worker = Thread.ofVirtual().start(held.next());
        assertThat(transport.resets.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.join(Duration.ofMillis(300)))
                .as("waiting for the channel")
                .isFalse();

        actor.close();
        assertThat(worker.join(Duration.ofSeconds(WAIT_SECONDS)))
                .as("the reset's worker, once the actor is closed")
                .isTrue();
        assertThat(held.tasks.poll(300, TimeUnit.MILLISECONDS))
                .as("work after the close")
                .isNull();
    }

    @Test
    void anAliveIsNeverDroppedForWantOfRoom() throws InterruptedException, ExecutionException, TimeoutException {
        // not started: nothing takes from the queues, and the requests' queue holds two
        var actor = new RecoveryActor(
                producers, Harness.settings(), api, events(), workers, InstantSource.system(), new Random(1), 2, 2);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        assertThat(actor.recoverEvent(PRE, MATCH, false)).isNotDone();
        assertThat(actor.recoverEvent(PRE, MATCH, false)).isNotDone();
        assertThat(actor.recoverEvent(PRE, MATCH, false).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the third, with no room")
                .isNull();
        // the only word of a gap
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, false);
        assertThat(actor.counters().factsDropped()).isEqualTo(1);
        actor.start();
        actor.up();
        ProducerStatusChange status = requireNonNull(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(status.cause()).isEqualTo(StatusCause.UNSUBSCRIBED);
    }

    @Test
    void aResetTheWorkersTurnAwayIsReportedAndTheRecoveriesAskedForAgain() throws InterruptedException {
        var gate = new Gate();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), gate);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);

        // the net's requests wait in the API until the reset is set to be turned away
        var held = new CountDownLatch(1);
        api.hold.set(held);
        long later = System.currentTimeMillis();
        session.processed(PRE, later - 300_000, later + 1, 0);
        session.processed(PRE, later - 300_000, later + 2, 0);
        api.next();
        api.next();
        gate.rejectNext = true;
        held.countDown();
        List<Request> again = List.of(api.next(), api.next());
        assertThat(again).as("asked for again, with no reset made").hasSize(2);
        assertThat(transport.resets.getCount()).as("no channel replaced").isEqualTo(1);
    }

    @Test
    void aSamplePostedAfterAnEssentialFactIsNotHandledBeforeIt() throws InterruptedException {
        var gate = new Gate();
        var actor = new RecoveryActor(producers, settings(), api, events(), gate);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);

        // the actor held inside a request, partway through a turn
        gate.armed = true;
        assertThat(actor.recoverEvent(LIVE, MATCH, false)).isNotDone();
        assertThat(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // the channel is lost, and the new one delivers at once
        long newer = System.currentTimeMillis() + 60_000;
        session.channelLost();
        session.channelReopened();
        session.processed(PRE, newer, newer, 0);
        gate.release.countDown();

        Request recovery = api.next();
        while (!(recovery.path().equals("recovery") && recovery.producer().equals("pre"))) {
            recovery = api.next();
        }
        assertThat(requireNonNull(recovery.after()).toEpochMilli())
                .as("from before the new channel's message, which came after the loss")
                .isLessThan(newer);
    }

    @Test
    void aSampleTakenAfterAnEssentialFactWasPostedIsHandledAfterIt() throws InterruptedException {
        var gate = new Gate();
        var actor = new RecoveryActor(producers, settings(), api, events(), gate);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);

        // between the actor's look at the essential queue and its take from the samples: the
        // channel is lost, and the new one delivers at once
        long newer = System.currentTimeMillis() + 60_000;
        var once = new AtomicBoolean();
        actor.beforeSamplePoll = () -> {
            if (once.compareAndSet(false, true)) {
                session.channelLost();
                session.channelReopened();
                session.processed(PRE, newer, newer, 0);
            }
        };
        long later = System.currentTimeMillis();
        actor.alive(PRE, later, later, true);

        Request recovery = api.next();
        while (!(recovery.path().equals("recovery") && recovery.producer().equals("pre"))) {
            recovery = api.next();
        }
        assertThat(once).as("the hook ran").isTrue();
        assertThat(requireNonNull(recovery.after()).toEpochMilli())
                .as("from before the new channel's message")
                .isLessThan(newer);
    }

    @Test
    void aRequestTheWorkersTurnAwayCountsAsFailedAndIsAskedForAgain() throws InterruptedException {
        var gate = new Gate();
        gate.rejectNext = true;
        var actor = new RecoveryActor(producers, settings(Duration.ofMillis(50), Duration.ZERO), api, events(), gate);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        assertThat(api.next().producer())
                .as("asked for again after the backoff")
                .isEqualTo("pre");
        assertThat(actor.counters().failed()).isEqualTo(1);
        assertThat(actor.counters().reissued()).isEqualTo(1);
    }

    @Test
    void aRequestThatFailsWithAnErrorCountsAsFailedAndIsAskedForAgain() throws InterruptedException {
        api.errorNext.set(true);
        var actor =
                new RecoveryActor(producers, settings(Duration.ofMillis(50), Duration.ZERO), api, events(), workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        Request first = api.next();
        Request again = api.next();
        assertThat(again.requestId()).as("asked for again after the backoff").isNotEqualTo(first.requestId());
        assertThat(actor.counters().failed()).isEqualTo(1);
    }

    @Test
    void aStreamOfEventRecoveryRequestsHoldsUpNoTurn() throws InterruptedException {
        var actor = new RecoveryActor(producers, settings(), api, events(), workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        // each request taken posts the next, so the requests' queue is never empty
        var turnsSeen = new java.util.concurrent.ConcurrentSkipListSet<Long>();
        var left = new AtomicInteger(2_500);
        var done = new CountDownLatch(1);
        actor.beforeRequestPoll = () -> {
            if (left.get() > 0) {
                // only while the stream runs: idle turns after it come on their own
                turnsSeen.add(actor.turns());
            }
            if (left.getAndDecrement() > 0) {
                assertThat(actor.recoverEvent(9, MATCH, false)).isNotNull();
            } else {
                done.countDown();
            }
        };
        assertThat(actor.recoverEvent(9, MATCH, false)).isNotNull();
        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(turnsSeen).as("turns the stream ran through").hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void aFactThatFailsIsCountedAndTheActorGoesOn() throws InterruptedException {
        var actor = new RecoveryActor(producers, settings(), api, events(), workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        // the session, the start and the connection handled first; the bad fact is the alive, not a tick
        awaitIdle(actor);
        var once = new AtomicBoolean(true);
        actor.beforeHandle = fact -> {
            if (fact instanceof RecoveryActor.Fact.Alives && once.compareAndSet(true, false)) {
                throw new IllegalStateException("a bad fact");
            }
        };
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.counters().factsFailed() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.counters().factsFailed()).isEqualTo(1);
        assertThat(actor.running()).isTrue();
        actor.alive(LIVE, now, now, true);
        assertThat(api.next().producer()).as("the next fact's request").isEqualTo("live");
    }

    @Test
    void aRequestTheWorkersFailOnWithAnErrorCountsAsFailedAndIsAskedForAgain() throws InterruptedException {
        var broken = new AtomicBoolean(true);
        Executor workers = task -> {
            if (broken.compareAndSet(true, false)) {
                throw new AssertionError("broken workers");
            }
            Thread.ofVirtual().start(task);
        };
        var actor =
                new RecoveryActor(producers, settings(Duration.ofMillis(50), Duration.ZERO), api, events(), workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        assertThat(api.next().producer())
                .as("asked for again after the backoff")
                .isEqualTo("pre");
        assertThat(actor.counters().failed()).isEqualTo(1);
        assertThat(actor.counters().factsFailed()).isZero();
    }

    @Test
    void aResetTheWorkersFailOnWithAnErrorIsReportedAsNotMade() throws InterruptedException {
        var gate = new Gate();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), gate);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        bothUp(actor, session);
        var held = new CountDownLatch(1);
        api.hold.set(held);
        stale(session);
        api.next();
        api.next();
        gate.errorNext = true;
        held.countDown();
        assertThat(List.of(api.next(), api.next()))
                .as("asked for again, with no reset made")
                .hasSize(2);
        assertThat(transport.resets.getCount()).as("no channel replaced").isEqualTo(1);
        assertThat(actor.counters().factsFailed()).isZero();
    }

    @Test
    void aResetIsReportedDoneOnlyOnceTheSessionHasAChannelAgain() throws InterruptedException {
        var gate = new Gate();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), gate);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        bothUp(actor, session);
        // the new channel cannot be opened at once: the transport opens it later
        transport.reopenLater = true;
        stale(session);
        api.next();
        api.next();
        assertThat(transport.resets.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(api.requests.poll(500, TimeUnit.MILLISECONDS))
                .as("asked for while no one reads the session")
                .isNull();
        transport.open = true;
        assertThat(List.of(api.next(), api.next()))
                .as("asked for once it reads again")
                .hasSize(2);
    }

    @Test
    void aFloodOfAlivesTakesOneSlotPerProducerAndAnUnsubscribedOneInItStillOpensTheGap() throws InterruptedException {
        var wedge = new CountDownLatch(1);
        var wedging = new AtomicBoolean();
        var wedged = new CountDownLatch(1);
        var slow = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                statuses.add(change);
                if (wedging.compareAndSet(true, false)) {
                    wedged.countDown();
                    try {
                        assertThat(wedge.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        var actor = new RecoveryActor(producers, settings(), api, slow, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        bothUp(actor, session);

        // the actor held in a status change of the live producer, and a flood of prematch alives
        wedging.set(true);
        long now = System.currentTimeMillis();
        actor.alive(LIVE, now, now, false);
        assertThat(wedged.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        long base = now + 1_000;
        for (int i = 0; i < 100_000; i++) {
            actor.alive(PRE, base + i, now, i != 50_000);
        }
        assertThat(actor.queued()).as("essential facts queued for the flood").isLessThanOrEqualTo(1);
        wedge.countDown();

        Request recovery = api.next();
        while (!(recovery.path().equals("recovery") && recovery.producer().equals("pre"))) {
            recovery = api.next();
        }
        assertThat(requireNonNull(recovery.after()).toEpochMilli())
                .as("from the last subscribed alive before the unsubscribed one")
                .isEqualTo(base + 49_999);
    }

    /** Both producers brought up, each by its first recovery. */
    private void bothUp(RecoveryActor actor, SessionFacts session) throws InterruptedException {
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
    }

    /** Two samples past the limit, which the window, nothing, lets the safety net act on at once. */
    private static void stale(SessionFacts session) {
        long later = System.currentTimeMillis();
        session.processed(PRE, later - 300_000, later + 1, 0);
        session.processed(PRE, later - 300_000, later + 2, 0);
    }

    @Test
    void aRequestTheWorkersFailOnCountsAsFailedAndIsAskedForAgain() throws InterruptedException {
        var broken = new AtomicBoolean(true);
        Executor workers = task -> {
            if (broken.compareAndSet(true, false)) {
                throw new IllegalStateException("broken workers");
            }
            Thread.ofVirtual().start(task);
        };
        var actor =
                new RecoveryActor(producers, settings(Duration.ofMillis(50), Duration.ZERO), api, events(), workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        assertThat(api.next().producer())
                .as("asked for again after the backoff")
                .isEqualTo("pre");
        assertThat(actor.counters().failed()).isEqualTo(1);
        assertThat(actor.counters().factsFailed()).isZero();
    }

    @Test
    void alivesOfAProducerTheListDoesNotHaveQueueNothing() throws InterruptedException {
        var wedge = new CountDownLatch(1);
        var wedged = new CountDownLatch(1);
        var slow = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                wedged.countDown();
                try {
                    assertThat(wedge.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var actor = new RecoveryActor(producers, settings(), api, slow, workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, false);
        assertThat(wedged.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100_000; i++) {
            actor.alive(9 + i % 1_000, now, now, i % 2 == 0);
        }
        assertThat(actor.queued()).as("essential facts queued for them").isZero();
        assertThat(actor.counters().unknownProducers()).isEqualTo(100_000);
        wedge.countDown();
    }

    @Test
    void aResetWaitingForItsChannelEndsWhenTheSessionCloses() throws InterruptedException {
        var held = new Held();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, events(), held);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        held.runNext();
        held.runNext();
        for (Request first : List.of(api.next(), api.next())) {
            session.snapshotComplete(producerOf(first), first.requestId());
        }
        awaitUp(PRE);
        awaitUp(LIVE);
        transport.reopenLater = true;
        stale(session);
        held.runNext();
        held.runNext();
        api.next();
        api.next();
        Thread worker = Thread.ofVirtual().start(held.next());
        assertThat(transport.resets.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.join(Duration.ofMillis(300)))
                .as("waiting for the channel")
                .isFalse();

        session.closed();
        assertThat(worker.join(Duration.ofSeconds(WAIT_SECONDS)))
                .as("the reset's worker, once the session is gone")
                .isTrue();
        assertThat(held.tasks.poll(300, TimeUnit.MILLISECONDS))
                .as("work for the closed session")
                .isNull();
    }

    @Test
    void aStreamOfSamplesHoldsUpNoTurn() throws InterruptedException {
        var actor = new RecoveryActor(producers, settings(), api, events(), workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        // each sample taken posts the next, so the samples' queue is never empty
        var turnsSeen = new java.util.concurrent.ConcurrentSkipListSet<Long>();
        var left = new AtomicInteger(2_500);
        var done = new CountDownLatch(1);
        actor.beforeSamplePoll = () -> {
            if (left.get() > 0) {
                turnsSeen.add(actor.turns());
            }
            if (left.getAndDecrement() > 0) {
                long now = System.currentTimeMillis();
                session.processed(PRE, now, now, 0);
            } else {
                done.countDown();
            }
        };
        long now = System.currentTimeMillis();
        session.processed(PRE, now, now, 0);
        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(turnsSeen).as("turns the stream ran through").hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfWaiting() throws InterruptedException, ExecutionException, TimeoutException {
        // not started: nothing takes from the queues
        var actor = new RecoveryActor(
                producers, Harness.settings(), api, events(), workers, InstantSource.system(), new Random(1), 2, 2);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        session.processed(PRE, 1, 1, 0);
        session.processed(PRE, 2, 2, 0);
        session.processed(PRE, 3, 3, 0);
        assertThat(actor.counters().factsDropped()).as("a sample with no room").isEqualTo(1);
        assertThat(actor.recoverEvent(PRE, MATCH, false)).isNotDone();
        assertThat(actor.recoverEvent(PRE, MATCH, false)).isNotDone();
        assertThat(actor.recoverEvent(PRE, MATCH, false).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("an event recovery with no room")
                .isNull();
        assertThat(actor.counters().factsDropped()).isEqualTo(2);

        // what would leave the state wrong for good is never dropped, and keeps its order
        actor.down("lost");
        session.snapshotComplete(PRE, 1);
        session.channelLost();
        session.channelReopened();
        actor.openSession(new SessionInfo(2, MessageInterest.ALL, true), transport);
        assertThat(actor.counters().factsDropped()).isEqualTo(2);
        actor.start();
        actor.up();
        ProducerStatusChange first = requireNonNull(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(first.cause()).as("the lost connection, first").isEqualTo(StatusCause.CONNECTION_LOST);
    }

    @Test
    void closingAnswersEveryoneStillWaitingAndTurnsAwayWhatComesAfter()
            throws InterruptedException, ExecutionException, TimeoutException {
        var actor = new RecoveryActor(
                producers, Harness.settings(), api, events(), workers, InstantSource.system(), new Random(1), 10, 10);
        var waiting = actor.recoverEvent(PRE, MATCH, false);
        actor.close();
        assertThat(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS)).isNull();
        assertThat(actor.recoverEvent(PRE, MATCH, false).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isNull();

        RecoveryActor started = actor(settings());
        started.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        started.start();
        started.up();
        var held = new CountDownLatch(1);
        api.hold.set(held);
        var inFlight = started.recoverEvent(LIVE, MATCH, false);
        api.next();
        started.close();
        held.countDown();
        assertThat(inFlight.get(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the API answers after the close")
                .isNull();
    }

    @Test
    void aCloseBetweenTheStartAndItsThreadLeavesTheMachineToTheThreadToCloseOnce() throws InterruptedException {
        RecoveryActor actor = actor(settings());
        var closedBy = new ConcurrentLinkedQueue<String>();
        var closedOnTheActor = new CountDownLatch(1);
        actor.beforeMachineClose = () -> {
            String thread = Thread.currentThread().getName();
            closedBy.add(thread);
            if (thread.equals("oddsfeed-recovery")) {
                closedOnTheActor.countDown();
            }
        };
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        // the close comes once the start has begun, before the thread it starts runs
        actor.beforeThreadStart = actor::close;
        actor.start();

        assertThat(closedOnTheActor.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(closedBy).as("the threads that closed the machine").containsExactly("oddsfeed-recovery");
    }

    @Test
    void aCloseBetweenTheStartAndItsThreadLeavesNothingToBePublishedAfterItReturns() throws InterruptedException {
        // with an initial snapshot interval the start would publish a point of its own
        RecoveryActor actor = actor(withInactivity(Harness.settings().maxInactivity(), Duration.ofMinutes(30)));
        Producer held = requireNonNull(producers.getProducer(PRE));
        var closedOnTheActor = new CountDownLatch(1);
        actor.beforeMachineClose = closedOnTheActor::countDown;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.beforeThreadStart = actor::close;
        actor.start();
        assertThat(held.getTimestampForRecovery()).as("when close() returned").isNull();

        assertThat(closedOnTheActor.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(held.getTimestampForRecovery())
                .as("once the thread has closed the machine")
                .isNull();
    }

    @Test
    void aStartAfterTheCloseOrASecondStartDoesNothing() throws InterruptedException {
        var closes = new AtomicInteger();
        RecoveryActor closed = actor(settings());
        closed.beforeMachineClose = closes::incrementAndGet;
        closed.close();
        closed.close();
        closed.start();
        assertThat(closed.threadStarted()).as("the closed actor's thread").isFalse();
        assertThat(closes).as("machine closes, closed twice").hasValue(1);

        RecoveryActor started = actor(settings());
        started.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        started.start();
        started.start();
        started.up();
        long now = System.currentTimeMillis();
        started.alive(PRE, now, now, true);
        assertThat(api.next().producer()).as("after the second start").isEqualTo("pre");
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheActor() throws InterruptedException {
        var throwing = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                throw new IllegalStateException("a listener bug");
            }
        };
        RecoveryActor actor = new RecoveryActor(producers, Harness.settings(), api, throwing, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        session.snapshotComplete(PRE, api.next().requestId());
        actor.alive(LIVE, now, now, true);
        assertThat(api.next().producer()).as("after the listener threw").isEqualTo("live");
        assertThat(actor.counters().factsFailed()).isZero();
    }

    @Test
    void theActorRunsOnItsOwnNamedThread() throws InterruptedException {
        var thread = new AtomicBoolean();
        var seen = new CountDownLatch(1);
        var listener = new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                thread.set(Thread.currentThread().getName().equals("oddsfeed-recovery"));
                seen.countDown();
            }
        };
        RecoveryActor actor = new RecoveryActor(producers, Harness.settings(), api, listener, workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, false);
        assertThat(seen.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(thread).isTrue();
    }

    // ---- what the actor tells

    @Test
    void theStatusEventIsToldWhenTheDownFlagOrPublicReasonChangesAndTheCauseEventOnEveryChange()
            throws InterruptedException {
        // KD-2: an alive saying a producer still down is unsubscribed changes the cause only
        var told = new Told();
        var actor = new RecoveryActor(producers, settings(), api, told, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.PREMATCH_ONLY, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, false);
        assertThat(told.nextCause()).isEqualTo(StatusCause.UNSUBSCRIBED);
        session.snapshotComplete(PRE, api.next().requestId());
        assertThat(told.nextCause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);

        // down for the connection, a public change; then for the channel too, the same public reason
        actor.down("lost");
        assertThat(told.nextCause()).isEqualTo(StatusCause.CONNECTION_LOST);
        session.channelLost();
        assertThat(told.nextCause()).isEqualTo(StatusCause.CHANNEL_LOST);

        // the status event of a change is told before its cause event, so every one is in by now
        assertThat(told.statuses)
                .filteredOn(change -> change.producerId() == PRE)
                .extracting(ProducerStatusChange::cause, ProducerStatusChange::down)
                .containsExactly(
                        tuple(StatusCause.FIRST_RECOVERY_COMPLETED, false), tuple(StatusCause.CONNECTION_LOST, true));
    }

    @Test
    void aSessionThatLagsWithTheResetsSpentAndCatchesUpIsTold() throws InterruptedException {
        // no resets at all, and a window of nothing: the first stale sample lags the session
        RecoverySettings design = settings(Harness.settings().firstReissueBackoff(), Duration.ZERO);
        var noResets = new RecoverySettings(
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
                0,
                design.firstResetBackoff(),
                design.eventRecoveries(),
                design.tick());
        var told = new Told();
        var actor = new RecoveryActor(producers, noResets, api, told, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.PREMATCH_ONLY, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        session.snapshotComplete(PRE, api.next().requestId());
        assertThat(told.nextCause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);

        stale(session);
        assertThat(told.next()).isEqualTo("session 1 lagging");
        long later = System.currentTimeMillis();
        session.processed(PRE, later, later + 3, 0);
        assertThat(told.next()).isEqualTo("session 1 caught up");
        // the stale samples may trip the processing delay until the next tick, so wait for it
        awaitUp(PRE);
        assertThat(api.requests).as("nothing asked for").isEmpty();
    }

    @Test
    void aSafetyNetRequestTheApiRefusedIsToldAndResetsNothing() throws InterruptedException {
        var told = new Told();
        var actor = new RecoveryActor(
                producers, settings(Harness.settings().firstReissueBackoff(), Duration.ZERO), api, told, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.PREMATCH_ONLY, true), transport);
        actor.start();
        actor.up();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        session.snapshotComplete(PRE, api.next().requestId());
        assertThat(told.nextCause()).isEqualTo(StatusCause.FIRST_RECOVERY_COMPLETED);

        api.refuse.set(true);
        stale(session);
        api.next();
        assertThat(told.next()).startsWith("session 1 not reset for producer 1: ");
        assertThat(transport.resets.getCount()).as("no channel replaced").isEqualTo(1);
        assertThat(actor.counters().resetRequestsFailed()).isEqualTo(1);
    }

    // ---- the doubles

    private final Producers producers = new Producers(list());

    private static RAProducers list() {
        var list = new RAProducers();
        for (long id : new long[] {PRE, LIVE}) {
            var producer = new RAProducer();
            producer.setId(id);
            producer.setName(id == PRE ? "pre" : "live");
            producer.setDescription("feed");
            producer.setApiUrl("https://api.example.invalid/v1");
            producer.setActive(true);
            producer.setScope(id == PRE ? "prematch" : "live");
            producer.setStatefulRecoveryWindowInMinutes(4320);
            list.getProducer().add(producer);
        }
        return list;
    }

    private static RecoverySettings settings() {
        return settings(
                Harness.settings().firstReissueBackoff(), Harness.settings().staleWindow());
    }

    /** The design's numbers, with a tick of 10 ms, so the actor looks at the time often. */
    private static RecoverySettings settings(Duration firstReissueBackoff, Duration staleWindow) {
        return settings(firstReissueBackoff, staleWindow, Duration.ofMillis(10));
    }

    private static RecoverySettings settings(Duration firstReissueBackoff, Duration staleWindow, Duration tick) {
        RecoverySettings settings = Harness.settings();
        return new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
                settings.snapshotCompleteTimeout(),
                settings.initialSnapshotInterval(),
                settings.nodeId(),
                settings.reissues(),
                firstReissueBackoff,
                settings.cooldown(),
                settings.aliveInterval(),
                settings.staleLimit(),
                staleWindow,
                settings.resets(),
                settings.firstResetBackoff(),
                settings.eventRecoveries(),
                tick);
    }

    /** The design's numbers, with another maximum inactivity and initial snapshot interval. */
    private static RecoverySettings withInactivity(Duration maxInactivity, @Nullable Duration initialSnapshotInterval) {
        RecoverySettings settings = settings();
        return new RecoverySettings(
                maxInactivity,
                settings.maxRecoveryTime(),
                settings.snapshotCompleteTimeout(),
                initialSnapshotInterval,
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
    }

    private RecoveryActor actor(RecoverySettings settings) {
        var actor = new RecoveryActor(producers, settings, api, events(), workers);
        this.actor = actor;
        return actor;
    }

    private RecoveryEvents events() {
        return new RecoveryEvents() {
            @Override
            public void producerCause(ProducerStatusChange change) {
                statuses.add(change);
            }

            @Override
            public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
                events.add("event recovery " + requestId + " completed");
            }

            @Override
            public void safetyNetReset(int session, long producerId, long ageMillis) {
                events.add("reset");
            }
        };
    }

    private void awaitUp(long producer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (producers.isProducerDown(producer) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(producers.isProducerDown(producer))
                .as("producer " + producer + " down")
                .isFalse();
    }

    /** Waits until the actor has handled every essential fact posted so far. */
    private static void awaitIdle(RecoveryActor actor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.queued() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        // a turn begun after the queue was empty: the one that took the last fact has ended
        long turns = actor.turns();
        while (actor.turns() < turns + 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.queued()).as("essential facts waiting").isZero();
    }

    private static long producerOf(Request request) {
        return request.producer().equals("pre") ? PRE : LIVE;
    }

    /** Every event the actor tells, as it tells it: the status changes by kind, the rest as text. */
    private static final class Told implements RecoveryEvents {
        final BlockingQueue<ProducerStatusChange> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<ProducerStatusChange> causes = new LinkedBlockingQueue<>();
        final BlockingQueue<String> others = new LinkedBlockingQueue<>();

        @Override
        public void producerStatus(ProducerStatusChange change) {
            statuses.add(change);
        }

        @Override
        public void producerCause(ProducerStatusChange change) {
            causes.add(change);
        }

        @Override
        public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
            others.add("event recovery " + requestId + " completed");
        }

        @Override
        public void safetyNetReset(int session, long producerId, long ageMillis) {
            others.add("session " + session + " reset for producer " + producerId);
        }

        @Override
        public void safetyNetRequestFailed(int session, long producerId, String reason) {
            others.add("session " + session + " not reset for producer " + producerId + ": " + reason);
        }

        @Override
        public void lagging(int session, boolean lagging) {
            others.add("session " + session + (lagging ? " lagging" : " caught up"));
        }

        /** The next cause of the prematch producer, within the wait. */
        StatusCause nextCause() throws InterruptedException {
            while (true) {
                ProducerStatusChange change =
                        requireNonNull(causes.poll(WAIT_SECONDS, TimeUnit.SECONDS), "a cause within the wait");
                if (change.producerId() == PRE) {
                    return change.cause();
                }
            }
        }

        /** The next event other than a status change, within the wait. */
        String next() throws InterruptedException {
            return requireNonNull(others.poll(WAIT_SECONDS, TimeUnit.SECONDS), "an event within the wait");
        }
    }

    /** Workers that run each task on a thread of its own, unless told to hold or to turn one away. */
    private static final class Gate implements Executor {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        /** Whether the next task holds the one handing it over until released. */
        volatile boolean armed;
        /** Whether the next task is turned away. */
        volatile boolean rejectNext;
        /** Whether handing the next task over fails with an error. */
        volatile boolean errorNext;

        @Override
        public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException("turned away");
            }
            if (errorNext) {
                errorNext = false;
                throw new AssertionError("broken workers");
            }
            if (armed) {
                armed = false;
                entered.countDown();
                try {
                    assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Thread.ofVirtual().start(task);
        }
    }

    /** Workers that hold every task until the test runs it. */
    private static final class Held implements Executor {
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        Runnable next() throws InterruptedException {
            return requireNonNull(tasks.poll(WAIT_SECONDS, TimeUnit.SECONDS), "a task within the wait");
        }

        /** Runs the next task here, on the test's thread. */
        void runNext() throws InterruptedException {
            next().run();
        }
    }

    /** One request the API got. */
    private record Request(
            String producer,
            String path,
            long requestId,
            @Nullable Instant after,
            String thread) {}

    /** An API that records each request, accepts it or refuses it. */
    private static final class Api implements RecoveryRequests {
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        final AtomicBoolean refuse = new AtomicBoolean();
        /** Whether the next request fails with an error rather than the API's refusal. */
        final AtomicBoolean errorNext = new AtomicBoolean();
        /** What a request waits for before it answers; null for nothing. */
        final AtomicReference<@Nullable CountDownLatch> hold = new AtomicReference<>();

        Request next() throws InterruptedException {
            return requireNonNull(requests.poll(WAIT_SECONDS, TimeUnit.SECONDS), "a request within the wait");
        }

        @Override
        public void postRecovery(String producer, long requestId, @Nullable Instant after) {
            record(new Request(
                    producer,
                    "recovery",
                    requestId,
                    after,
                    Thread.currentThread().getName()));
        }

        @Override
        public void postEventOddsRecovery(String producer, URN eventId, long requestId) {
            record(new Request(
                    producer,
                    "odds " + eventId,
                    requestId,
                    null,
                    Thread.currentThread().getName()));
        }

        @Override
        public void postEventStatefulRecovery(String producer, URN eventId, long requestId) {
            record(new Request(
                    producer,
                    "stateful " + eventId,
                    requestId,
                    null,
                    Thread.currentThread().getName()));
        }

        private void record(Request request) {
            requests.add(request);
            if (errorNext.compareAndSet(true, false)) {
                throw new AssertionError("a broken client");
            }
            CountDownLatch held = hold.get();
            if (held != null) {
                try {
                    if (!held.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("held past the wait");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (refuse.get()) {
                throw new ApiException("503");
            }
        }
    }

    /** A session's channel that counts its resets. */
    private static final class Transport implements SessionTransport {
        final SessionQueue queue = Queues.holding(10, 3);
        final CountDownLatch resets = new CountDownLatch(1);
        final AtomicInteger epoch = new AtomicInteger();
        volatile @Nullable String resetThread;
        /** What the next reset fails with, before it replaces anything; null for nothing. */
        volatile @Nullable Throwable failWith;
        /** What a reset fails with once it has moved the epoch; null for nothing. */
        volatile @Nullable Throwable failAfterMoving;

        volatile boolean open = true;
        /** Whether a reset leaves the channel closed, for the transport to open later. */
        volatile boolean reopenLater;

        @Override
        public void ack(RawDelivery delivery) {}

        @Override
        public void reset() {
            Throwable failure = failWith;
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            resetThread = Thread.currentThread().getName();
            if (reopenLater) {
                open = false;
            }
            epoch.incrementAndGet();
            resets.countDown();
            Throwable late = failAfterMoving;
            if (late instanceof Error error) {
                throw error;
            }
            if (late instanceof RuntimeException exception) {
                throw exception;
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public long epoch() {
            return epoch.get();
        }

        @Override
        public SessionQueue queue() {
            return queue;
        }
    }
}
