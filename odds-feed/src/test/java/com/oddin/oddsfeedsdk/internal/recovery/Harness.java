package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The state machine with a clock the test moves and every output captured: the requests, the
 * resets and the events. The producer list has producer 1, prematch, and producer 2, live, each with
 * a stateful recovery window of three days.
 */
final class Harness {

    static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    static final long PRE = 1;
    static final long LIVE = 2;
    static final int NODE = 7;
    static final URN MATCH = URN.parse("od:match:1");

    final Clock clock = new Clock();
    final RecoverySettings settings;
    final Producers producers;
    final RecoveryCounters counters = new RecoveryCounters();
    final List<Outbox.Call> calls = new ArrayList<>();
    final List<Integer> resets = new ArrayList<>();
    final List<Long> resetNumbers = new ArrayList<>();
    /** Every status change, with its cause. */
    final List<ProducerStatusChange> statuses = new ArrayList<>();
    /** The status changes 0.0.x's callback fires for: the down flag or the public reason changed. */
    final List<ProducerStatusChange> publicStatuses = new ArrayList<>();

    final List<String> events = new ArrayList<>();
    /** The sessions opened, for the alives in their queues. */
    final List<Integer> sessions = new ArrayList<>();

    final RecoveryMachine machine;

    Harness() {
        this(settings());
    }

    Harness(RecoverySettings settings) {
        this.settings = settings;
        this.producers = new Producers(list(), clock);
        Outbox outbox = new Outbox() {
            @Override
            public void request(Call call) {
                calls.add(call);
            }

            @Override
            public void reset(int session, long number) {
                resets.add(session);
                resetNumbers.add(number);
            }

            @Override
            public void reply(CompletableFuture<@Nullable Long> reply, @Nullable Long requestId) {
                reply.complete(requestId);
            }

            @Override
            public void fail(CompletableFuture<@Nullable Long> reply, RuntimeException failure) {
                reply.completeExceptionally(failure);
            }
        };
        RecoveryEvents recorded = new RecoveryEvents() {
            @Override
            public void producerStatus(ProducerStatusChange change) {
                publicStatuses.add(change);
            }

            @Override
            public void producerCause(ProducerStatusChange change) {
                statuses.add(change);
            }

            @Override
            public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
                events.add("event recovery " + requestId + " of " + eventId + " completed");
            }

            @Override
            public void safetyNetReset(int session, long producerId, long ageMillis) {
                events.add("session " + session + " reset for producer " + producerId);
            }

            @Override
            public void safetyNetRequestFailed(int session, long producerId, String reason) {
                events.add("session " + session + " not reset for producer " + producerId);
            }

            @Override
            public void lagging(int session, boolean lagging) {
                events.add("session " + session + (lagging ? " lagging" : " caught up"));
            }
        };
        this.machine = new RecoveryMachine(producers, settings, outbox, recorded, clock, counters, new Random(42));
    }

    /** The design's numbers, with 0.0.x's defaults for what the configuration sets. */
    static RecoverySettings settings() {
        return new RecoverySettings(
                Duration.ofSeconds(20),
                Duration.ofMinutes(360),
                Duration.ofMinutes(5),
                null,
                NODE,
                3,
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofSeconds(10),
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                3,
                Duration.ofMinutes(1),
                128,
                Duration.ofSeconds(1));
    }

    /** The design's numbers, with another maximum inactivity. */
    static RecoverySettings settings(Duration maxInactivity) {
        RecoverySettings settings = settings();
        return new RecoverySettings(
                maxInactivity,
                settings.maxRecoveryTime(),
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
                settings.tick());
    }

    private static RAProducers list() {
        var list = new RAProducers();
        list.getProducer().add(producer(PRE, "pre", "prematch"));
        list.getProducer().add(producer(LIVE, "live", "live"));
        return list;
    }

    private static RAProducer producer(long id, String name, String scope) {
        var producer = new RAProducer();
        producer.setId(id);
        producer.setName(name);
        producer.setDescription(name + " feed");
        producer.setApiUrl("https://api.example.invalid/v1/" + name);
        producer.setActive(true);
        producer.setScope(scope);
        producer.setStatefulRecoveryWindowInMinutes(4320);
        return producer;
    }

    long now() {
        return clock.millis();
    }

    /** Moves the clock, and ticks the machine once at the end, as the actor does at least once a second. */
    void advance(Duration by) {
        clock.advance(by);
        machine.tick();
    }

    /**
     * Moves the clock second by second, ticking each time, with an alive from both producers every
     * ten seconds of the clock, on the alive channel and in every session's queue.
     */
    void runWithAlives(Duration total) {
        for (long second = 1; second <= total.toSeconds(); second++) {
            clock.advance(Duration.ofSeconds(1));
            if (Duration.between(START, clock.instant()).toSeconds() % 10 == 0) {
                for (long producer : List.of(PRE, LIVE)) {
                    alive(producer);
                    for (int session : sessions) {
                        sessionAlive(session, producer);
                    }
                }
            }
            machine.tick();
        }
    }

    /** The feed open, and the transport up with every session's channel bound. */
    void start() {
        machine.start();
        machine.connectionUp();
    }

    void open(int session, MessageInterest interest) {
        open(new SessionInfo(session, interest, true));
    }

    void open(SessionInfo session) {
        sessions.add(session.id());
        machine.sessionOpened(session);
    }

    void close(int session) {
        sessions.remove(Integer.valueOf(session));
        machine.sessionClosed(session);
    }

    /** A subscribed alive generated now, as the alive channel sees it. */
    void alive(long producer) {
        machine.alive(producer, now(), now(), true);
    }

    void unsubscribed(long producer) {
        machine.alive(producer, now(), now(), false);
    }

    /** A session took a live message generated {@code agedBy} ago, now. */
    void live(int session, long producer, Duration agedBy) {
        machine.processed(session, producer, now() - agedBy.toMillis(), now(), false);
    }

    void sessionAlive(int session, long producer) {
        machine.sessionAlive(session, producer, now(), now(), true);
    }

    /** The producer's snapshot requests so far. */
    List<Outbox.Call.Snapshot> snapshots(long producer) {
        return calls.stream()
                .filter(call -> call instanceof Outbox.Call.Snapshot && call.producerId() == producer)
                .map(Outbox.Call.Snapshot.class::cast)
                .toList();
    }

    Outbox.Call.Snapshot lastSnapshot(long producer) {
        List<Outbox.Call.Snapshot> snapshots = snapshots(producer);
        if (snapshots.isEmpty()) {
            throw new AssertionError("no recovery of producer " + producer + " was asked for");
        }
        return snapshots.getLast();
    }

    /** The transport reports the last reset done. */
    void resetDone() {
        machine.resetDone(resets.getLast(), resetNumbers.getLast(), true);
    }

    /** The transport reports it could not take the last reset at all. */
    void resetRefused() {
        machine.resetDone(resets.getLast(), resetNumbers.getLast(), false);
    }

    void accept(Outbox.Call call) {
        machine.answered(call.requestId(), null);
    }

    void refuse(Outbox.Call call) {
        machine.answered(call.requestId(), new ApiException("503"));
    }

    /** The recovery accepted, and its snapshot complete seen by these sessions. */
    void complete(Outbox.Call call, int... sessions) {
        accept(call);
        for (int session : sessions) {
            machine.snapshotComplete(session, call.producerId(), call.requestId());
        }
    }

    /** The producer's status changes so far. */
    List<ProducerStatusChange> statuses(long producer) {
        return statuses.stream()
                .filter(change -> change.producerId() == producer)
                .toList();
    }

    @Nullable
    ProducerStatusChange lastStatus(long producer) {
        List<ProducerStatusChange> changes = statuses(producer);
        return changes.isEmpty() ? null : changes.getLast();
    }

    /** One session of {@code interest}, started, with the producers' first recoveries complete. */
    static Harness upWith(MessageInterest interest) {
        var harness = new Harness();
        harness.open(1, interest);
        harness.start();
        harness.bothUp(1);
        return harness;
    }

    /** Both producers brought up by their first recovery, seen by these sessions. */
    void bothUp(int... sessions) {
        alive(PRE);
        alive(LIVE);
        complete(lastSnapshot(PRE), sessions);
        complete(lastSnapshot(LIVE), sessions);
    }

    CompletableFuture<@Nullable Long> recoverEvent(long producer) {
        var reply = new CompletableFuture<@Nullable Long>();
        machine.recoverEvent(producer, MATCH, false, reply);
        return reply;
    }

    /** A clock the test moves. */
    static final class Clock implements InstantSource {
        private Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }
}
