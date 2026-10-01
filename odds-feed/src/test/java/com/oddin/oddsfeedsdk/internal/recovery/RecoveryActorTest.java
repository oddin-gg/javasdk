package com.oddin.oddsfeedsdk.internal.recovery;

import static com.oddin.oddsfeedsdk.internal.recovery.Harness.LIVE;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.MATCH;
import static com.oddin.oddsfeedsdk.internal.recovery.Harness.PRE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void aRequestTheApiRefusedIsAskedForAgain() throws InterruptedException {
        RecoveryActor actor =
                actor(settings(Duration.ofMillis(50), Harness.settings().staleWindow()));
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        api.refuse.set(true);
        actor.start();
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
        Long requestId = actor.recoverEvent(LIVE, MATCH, true).get(WAIT_SECONDS, TimeUnit.SECONDS);
        Request request = api.next();
        assertThat(request.path()).isEqualTo("stateful " + MATCH);
        assertThat(requestId).isEqualTo(request.requestId());
        session.snapshotComplete(LIVE, request.requestId());
        assertThat(events.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("event recovery " + requestId + " completed");
    }

    @Test
    void theSafetyNetsResetRunsOnAWorkerAndCountsWhatTheQueueHeld() throws InterruptedException {
        RecoveryActor actor = actor(settings(Harness.settings().firstReissueBackoff(), Duration.ZERO));
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, true);
        actor.alive(LIVE, now, now, true);
        session.snapshotComplete(PRE, api.next().requestId());
        session.snapshotComplete(LIVE, api.next().requestId());
        assertThat(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        // three deliveries the session has not taken, and two samples older than the limit, which
        // the window, nothing, lets act at once
        long later = System.currentTimeMillis();
        session.processed(PRE, later - 300_000, later + 1, false);
        session.processed(PRE, later - 300_000, later + 2, false);
        api.next();
        api.next();
        assertThat(transport.resets.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("reset")
                .isTrue();
        assertThat(transport.resetThread).isNotEqualTo("oddsfeed-recovery");
        assertThat(actor.counters().resets()).isEqualTo(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (actor.counters().resetDropped() != 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(actor.counters().resetDropped()).isEqualTo(3);
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfWaiting() throws InterruptedException, ExecutionException {
        // not started: nothing takes from the queues
        var actor = new RecoveryActor(
                producers, Harness.settings(), api, events(), workers, InstantSource.system(), new Random(1), 2, 2);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.alive(PRE, 1, 1, true);
        actor.alive(PRE, 2, 2, true);
        session.processed(PRE, 1, 1, false);
        session.processed(PRE, 2, 2, false);
        session.processed(PRE, 3, 3, false);
        assertThat(actor.counters().factsDropped()).isEqualTo(2);
        assertThat(actor.recoverEvent(PRE, MATCH, false).get())
                .as("an event recovery with no room")
                .isNull();
    }

    @Test
    void closingAnswersEveryoneStillWaitingAndTurnsAwayWhatComesAfter()
            throws InterruptedException, ExecutionException {
        var actor = new RecoveryActor(
                producers, Harness.settings(), api, events(), workers, InstantSource.system(), new Random(1), 10, 10);
        var waiting = actor.recoverEvent(PRE, MATCH, false);
        actor.close();
        assertThat(waiting.get()).isNull();
        assertThat(actor.recoverEvent(PRE, MATCH, false).get()).isNull();

        RecoveryActor started = actor(settings());
        started.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        started.start();
        var held = new CountDownLatch(1);
        api.hold.set(held);
        var inFlight = started.recoverEvent(LIVE, MATCH, false);
        api.next();
        started.close();
        held.countDown();
        assertThat(inFlight.get()).as("the API answers after the close").isNull();
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheActor() throws InterruptedException {
        var throwing = new RecoveryEvents() {
            @Override
            public void producerStatus(ProducerStatusChange change) {
                throw new IllegalStateException("a listener bug");
            }
        };
        RecoveryActor actor = new RecoveryActor(producers, Harness.settings(), api, throwing, workers);
        this.actor = actor;
        SessionFacts session = actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
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
            public void producerStatus(ProducerStatusChange change) {
                thread.set(Thread.currentThread().getName().equals("oddsfeed-recovery"));
                seen.countDown();
            }
        };
        RecoveryActor actor = new RecoveryActor(producers, Harness.settings(), api, listener, workers);
        this.actor = actor;
        actor.openSession(new SessionInfo(1, MessageInterest.ALL, true), transport);
        actor.start();
        long now = System.currentTimeMillis();
        actor.alive(PRE, now, now, false);
        assertThat(seen.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(thread).isTrue();
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
        RecoverySettings settings = Harness.settings();
        return new RecoverySettings(
                settings.maxInactivity(),
                settings.maxRecoveryTime(),
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
                Duration.ofMillis(10));
    }

    private RecoveryActor actor(RecoverySettings settings) {
        var actor = new RecoveryActor(producers, settings, api, events(), workers);
        this.actor = actor;
        return actor;
    }

    private RecoveryEvents events() {
        return new RecoveryEvents() {
            @Override
            public void producerStatus(ProducerStatusChange change) {
                statuses.add(change);
            }

            @Override
            public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
                events.add("event recovery " + requestId + " completed");
            }
        };
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

        @Override
        public void ack(RawDelivery delivery) {}

        @Override
        public void reset() {
            resetThread = Thread.currentThread().getName();
            epoch.incrementAndGet();
            resets.countDown();
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
