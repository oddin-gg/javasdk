package com.oddin.oddsfeedsdk.stress;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ConnectionState;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The connection and the session's queue are cut six times under one feed that delivers all the
 * while, with two readers calling the SDK; and the feed closes in the middle of its reconnecting.
 *
 * <p>Each cycle makes one fault: the broker closes every connection, deletes the session's queue and
 * leaves the connection, or refuses the login once or twice as the SDK comes back. They alternate
 * between the two ledgers of the lost-message contract: in a <em>barrier</em> cycle the publishers
 * stop and everything sent has been handled before the cut, so nothing is in flight and everything
 * published afterwards must arrive, in order, once; in a <em>chaos</em> cycle they run through the cut,
 * and whatever the old queue held is gone, but nothing may repeat or arrive out of order, and
 * everything published once the SDK is back must arrive. A chaos cycle also takes the session's
 * queue away again while its recovery waits for the snapshot.
 *
 * <p>After each: the connection is back and has been counted once, the recovery of the live producer
 * was asked for from a point no later than the last message the session handled, the producers are
 * up again, and the health never read STALLED. At the end the connection state is healthy, and a
 * listener that closes the feed as the connection comes up finds the feed closed, promptly, with
 * nothing left behind.
 */
@Tag("stress")
@ExtendWith(Seed.Report.class)
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ReconnectWhileDeliveringStressTest {

    private static final int CYCLES = 6;
    private static final int MATCHES = 4;
    private static final int READERS = 2;

    private static final Duration WAIT = Duration.ofSeconds(20);
    /** One reconnect takes a backoff of 1 s, 0.7 to 1.3 times, per refused try, and a login; this is the cap. */
    private static final Duration RECONNECT = Duration.ofSeconds(30);

    private static final Duration SHUTDOWN = Duration.ofSeconds(5);
    private static final Duration MARGIN = Duration.ofSeconds(2);

    private static final String LIVE = "/v1/live/recovery/initiate_request";
    private static final String PREMATCH = "/v1/pre/recovery/initiate_request";
    private static final String PROBE_MATCH = "od:match:9299";

    private enum Fault {
        CLOSE_CONNECTIONS(true, 0),
        DELETE_SESSION_QUEUES(false, 0),
        REFUSE_ONCE(true, 1),
        REFUSE_TWICE(true, 2);

        /** Whether the SDK has to make a new connection, which is what it counts as a reconnect. */
        final boolean connectionLevel;

        final int refusals;

        Fault(boolean connectionLevel, int refusals) {
            this.connectionLevel = connectionLevel;
            this.refusals = refusals;
        }
    }

    private static FakeFeed broker;

    @BeforeAll
    static void startTheBroker() {
        broker = FakeFeed.start();
    }

    @AfterAll
    static void stopTheBroker() {
        broker.close();
    }

    @Test
    void theFeedReconnectsWhileDeliveringAndLosesOnlyWhatTheContractAllows() throws Exception {
        long seed = Seed.value();
        System.out.printf("STRESS reconnect-while-delivering seed=%d cycles=%d%n", seed, CYCLES);
        var leaks = LeakCheck.begin().broker(broker);
        try (var api = FakeRestServer.start()) {
            leaks.rest(api);
            var run = new Run(api, seed);
            Throwable failure = null;
            try {
                run.execute();
            } catch (Throwable e) {
                failure = e;
                throw e;
            } finally {
                run.shutDown(failure);
            }
            run.afterClose();
        }
        leaks.assertClean(WAIT);
        assertThat(broker.unacknowledged()).as("unacknowledged after close").isZero();
    }

    /**
     * Closing while the SDK waits to reconnect, once in the long pause after a limit of connections
     * (a minute) and once in the short one after a refused login: nothing is left behind.
     */
    @Test
    void aCloseDuringTheBackoffOfAReconnectLeavesNothingBehind() throws Exception {
        try (var api = FakeRestServer.start()) {
            for (var why : List.of("limit", "refusal")) {
                var leaks = LeakCheck.begin().broker(broker).rest(api);
                var states = new CopyOnWriteArrayList<ConnectionStateChange>();
                long retryAt;
                var feed = StressFeed.feed(
                        new StressFeed.QuietEvents() {
                            @Override
                            public void onConnectionStateChange(ConnectionStateChange change) {
                                states.add(change);
                            }
                        },
                        broker,
                        api,
                        builder -> builder);
                feed.getSessionBuilder()
                        .setListener(new Recording())
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                feed.open();
                try {
                    StressFeed.awaitRouted(
                            () -> broker.publish(StressFeed.fixtureChange(PROBE_MATCH, 1)), "the probe", WAIT);
                    int refused = broker.refusedLogins().size();
                    if (why.equals("limit")) {
                        broker.limitConnections(0);
                    } else {
                        broker.refuseLogins();
                    }
                    broker.closeConnections();
                    // told as the backoff after a failed try begins: for a limit, the pause of a minute
                    long until = System.nanoTime() + RECONNECT.toNanos();
                    while (!inBackoff(states, why, broker.refusedLogins().size() - refused)
                            && System.nanoTime() < until) {
                        Thread.sleep(50);
                    }
                    assertThat(inBackoff(states, why, broker.refusedLogins().size() - refused))
                            .as("in the backoff after a %s: %s", why, states)
                            .isTrue();
                    long inBackoffAt = System.nanoTime();
                    retryAt = inBackoffAt + backoffOf(states).toNanos();
                    assertThat(StressFeed.timed(feed::close))
                            .as("close() during the backoff after a %s", why)
                            .isLessThan(SHUTDOWN.plus(MARGIN));
                } finally {
                    broker.limitConnections(-1);
                    broker.allowLogins();
                    feed.close();
                }
                leaks.assertClean(WAIT);
                // a worker that close() left asleep would retry as its pause ends, and no thread scan sees it
                while (System.nanoTime() < retryAt + MARGIN.toNanos()) {
                    assertThat(broker.openConnections())
                            .as("broker connections, while the pause of the %s lasts", why)
                            .isEmpty();
                    Thread.sleep(100);
                }
                leaks.assertClean(WAIT);
                assertThat(states.getLast().state())
                        .as("the last state told, once closed")
                        .isNotEqualTo(ConnectionState.UP);
            }
        }
    }

    /** The longest pause the SDK told it was about to take. */
    private static Duration backoffOf(List<ConnectionStateChange> states) {
        return states.stream()
                .filter(change -> change.state() == ConnectionState.RECOVERING)
                .map(ConnectionStateChange::retryIn)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    private static boolean inBackoff(List<ConnectionStateChange> states, String why, int refusals) {
        return states.stream()
                .anyMatch(change -> change.state() == ConnectionState.RECOVERING
                        && (why.equals("limit")
                                ? change.retryIn().compareTo(Duration.ofSeconds(30)) >= 0
                                : refusals >= 1 && change.attempt() >= 2));
    }

    /** A session's listener that notes nothing. */
    private static final class Recording implements StressFeed.OddsChanges {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {}
    }

    /** What a cycle's listener records into, and which ledger that is. */
    private record Current(Ledger ledger, OddsFeedListener recorder) {
        Current(Ledger ledger) {
            this(ledger, ledger.listener());
        }
    }

    private static final class Run {

        private final FakeRestServer api;
        private final long seed;
        private final long base = System.currentTimeMillis();
        private final AtomicReference<Current> current = new AtomicReference<>(new Current(new Ledger(base)));
        private final ReentrantReadWriteLock ledgerGate = new ReentrantReadWriteLock(true);
        private final ReentrantReadWriteLock aliveGate = new ReentrantReadWriteLock(true);
        private final DeadlockSentinel deadlock = new DeadlockSentinel(Duration.ofSeconds(15));
        private final AtomicReference<OddsFeed> feedRef = new AtomicReference<>();
        private final HealthSentinel health =
                new HealthSentinel(() -> requireNonNull(feedRef.get()).getHealth());

        private final AtomicBoolean stopLoad = new AtomicBoolean();
        private final AtomicBoolean holdSnapshots = new AtomicBoolean();
        private final AtomicReference<Runnable> onUp = new AtomicReference<>();
        private final AtomicLong lastDelivered = new AtomicLong();
        private final AtomicLong lastAlive = new AtomicLong();
        /**
         * For each recovery of the live producer the fake received, by its request id, the latest message handled
         * or alive sent then, and the time of the request: nothing it asks from may be later than either.
         */
        private final Map<String, long[]> recoveryBounds = new ConcurrentHashMap<>();

        private final AtomicInteger publishFailures = new AtomicInteger();
        private final AtomicInteger snapshotsSent = new AtomicInteger();
        private final List<ConnectionStateChange> states = new CopyOnWriteArrayList<>();
        private final List<String> fatal = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        private final List<Thread> threads = new ArrayList<>();
        private final List<Thread> readerThreads = new ArrayList<>();
        private final List<DeadlockSentinel.Worker> readers = new ArrayList<>();

        private OddsFeed feed;
        private int sessionId;
        private long expectedReconnects;

        Run(FakeRestServer api, long seed) {
            this.api = api;
            this.seed = seed;
        }

        // ------------------------------------------------------------------ the scenario

        void execute() throws Exception {
            feed = StressFeed.feed(new Events(), broker, api, builder -> builder);
            feedRef.set(feed);
            api.respondWith(request -> {
                if (request.method().equals("POST") && request.path().equals(LIVE)) {
                    recoveryBounds.put(
                            request.parameter("request_id"),
                            new long[] {Math.max(lastDelivered.get(), lastAlive.get()), System.currentTimeMillis()});
                }
                return null;
            });
            feed.getSessionBuilder()
                    .setListener(new Listener())
                    .setMessageInterest(MessageInterest.ALL)
                    .build();
            feed.open();
            deadlock.start();
            health.start();
            sessionId = feed.getHealth().sessions().getFirst().id();

            StressFeed.awaitRouted(() -> broker.publish(StressFeed.fixtureChange(PROBE_MATCH, 1)), "the probe", WAIT);
            startAlivesAndRecoveries();
            await(
                    "both producers up",
                    WAIT,
                    () -> !feed.getProducerManager().isProducerDown(1)
                            && !feed.getProducerManager().isProducerDown(2));
            startPublishers();
            startReaders();

            var faults = faults();
            for (int i = 0; i < CYCLES; i++) {
                cycle(i, faults.get(i), i % 2 == 0);
            }

            // the end of the six: the connection is healthy and was lost and found as often as it was cut
            await(
                    "the connection healthy",
                    WAIT,
                    () -> feed.getHealth().components().get(HealthComponent.CONNECTION) == HealthState.HEALTHY);
            assertThat(counters().get("transport.reconnects"))
                    .as("reconnects, over %d cycles; %s", CYCLES, dump())
                    .isEqualTo(expectedReconnects);
            assertThat(fatal).as("fatal errors").isEmpty();
            assertThat(unexpected).as("failures of the load's threads").isEmpty();
            health.assertNeverStalled();

            closeAsTheConnectionComesUp();
        }

        /** Four faults in an order of the seed's, and two more it picks. */
        private List<Fault> faults() {
            var random = new SplittableRandom(Seed.derive(seed, "faults"));
            var all = new ArrayList<>(List.of(Fault.values()));
            Collections.shuffle(all, new Random(random.nextLong()));
            while (all.size() < CYCLES) {
                all.add(all.get(random.nextInt(Fault.values().length)));
            }
            System.out.println("STRESS faults " + all);
            return all;
        }

        private void cycle(int index, Fault fault, boolean barrier) throws Exception {
            System.out.printf("STRESS cycle %d: %s, %s%n", index, fault, barrier ? "barrier" : "chaos");
            long started = System.nanoTime();
            ledgerGate.writeLock().lock();
            try {
                if (barrier) {
                    aliveGate.writeLock().lock();
                }
                // everything sent so far has been handled, so none of it can be told to the next ledger
                await("the session drained", WAIT, () -> session().queueDepth() == 0 && broker.unacknowledged() == 0);
                // stamped by the clock, as the session's alives are: what a recovery may ask from is then comparable
                // to the time of a message, and the cycle that follows begins seconds later than this one's stamps
                var ledger = new Current(new Ledger(System.currentTimeMillis()));
                current.set(ledger);
                long discards = counters().get(counter("epoch_discards"));
                long skipped = counters().get(counter("skipped_acks"));
                int liveBefore = api.requests("POST", LIVE).size();
                var lost = fault == Fault.DELETE_SESSION_QUEUES ? broker.sessionQueues() : broker.boundQueues();
                holdSnapshots.set(!barrier);
                if (!barrier) {
                    ledgerGate.writeLock().unlock();
                }

                inject(fault);
                if (fault.connectionLevel) {
                    expectedReconnects++;
                }
                assertThat(broker.awaitUnbound(lost, WAIT))
                        .as("the queues the fault took, unbound; %s", dump())
                        .isTrue();
                if (barrier) {
                    // alives flow again, as the producer sends them, while the SDK is away
                    aliveGate.writeLock().unlock();
                }
                awaitBack(fault, lost);
                api.awaitRequests("POST", LIVE, liveBefore + 1);
                if (!barrier) {
                    // a loss while the recovery waits for its snapshot, which is held back until now
                    var queues = broker.sessionQueues();
                    broker.deleteSessionQueues();
                    awaitNewSessionQueue(queues);
                    assertThat(broker.awaitUnbound(queues, WAIT))
                            .as("the queues of the second loss, unbound; %s", dump())
                            .isTrue();
                }
                var mark = ledger.ledger().mark();
                if (barrier) {
                    // nothing is published until the SDK is up and a probe has made the round trip
                    StressFeed.awaitRouted(() -> publishQuietly(ledger.ledger(), PROBE_MATCH), "the probe", WAIT);
                    assertThat(ledger.ledger().awaitCaughtUp(WAIT))
                            .as("the probe delivered; %s", dump())
                            .isTrue();
                    ledgerGate.writeLock().unlock();
                }
                holdSnapshots.set(false);
                await("traffic since the reconnect", WAIT, () -> ledger.ledger().publishedSince(mark) >= 3L * MATCHES);
                ledgerGate.writeLock().lock();

                if (barrier) {
                    assertThat(ledger.ledger().awaitCaughtUp(WAIT))
                            .as("everything delivered; %s", dump())
                            .isTrue();
                    ledger.ledger().assertExact();
                } else {
                    eventually(() -> ledger.ledger().assertGapsOnlyAfterReconnect(mark), WAIT);
                    // a send the broker answered with an error may have been routed all the same
                    long publishedSince = ledger.ledger().publishedSince(mark);
                    assertThat(ledger.ledger().deliveredSince(mark))
                            .as("delivered since the reconnect, of what was published then")
                            .isBetween(
                                    publishedSince,
                                    publishedSince + ledger.ledger().uncertainSince(mark))
                            .isPositive();
                }
                await(
                        "both producers up again",
                        WAIT,
                        () -> !feed.getProducerManager().isProducerDown(1)
                                && !feed.getProducerManager().isProducerDown(2));
                assertRecoveryPoints(liveBefore);
                await("the session drained", WAIT, () -> session().queueDepth() == 0 && broker.unacknowledged() == 0);
                assertThat(counters().get("transport.reconnects"))
                        .as("reconnects after cycle %d; %s", index, dump())
                        .isEqualTo(expectedReconnects);
                long lostMessages =
                        ledger.ledger().publishedCount() - ledger.ledger().deliveredCount();
                long discarded = counters().get(counter("epoch_discards")) - discards;
                long skippedAcks = counters().get(counter("skipped_acks")) - skipped;
                if (barrier) {
                    // nothing was in flight: nothing for the SDK to throw away, and nothing it could not acknowledge
                    assertThat(discarded)
                            .as("epoch_discards in a barrier cycle; %s", dump())
                            .isZero();
                    assertThat(skippedAcks)
                            .as("skipped_acks in a barrier cycle; %s", dump())
                            .isZero();
                }
                System.out.printf(
                        "STRESS cycle %d done in %d ms: %d of %d odds changes lost, epoch_discards %d, skipped_acks %d%n",
                        index,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        lostMessages,
                        ledger.ledger().publishedCount(),
                        discarded,
                        skippedAcks);
            } finally {
                holdSnapshots.set(false);
                release(aliveGate);
                release(ledgerGate);
            }
        }

        private void inject(Fault fault) throws InterruptedException {
            switch (fault) {
                case CLOSE_CONNECTIONS -> broker.closeConnections();
                case DELETE_SESSION_QUEUES -> broker.deleteSessionQueues();
                case REFUSE_ONCE, REFUSE_TWICE -> {
                    int refused = broker.refusedLogins().size();
                    broker.refuseLogins();
                    try {
                        broker.closeConnections();
                        await(
                                "the SDK's login refused " + fault.refusals + " time(s)",
                                RECONNECT,
                                () -> broker.refusedLogins().size() >= refused + fault.refusals);
                    } finally {
                        broker.allowLogins();
                    }
                }
            }
        }

        /** The SDK is connected again, within what its backoffs allow, and has declared the session's queue anew. */
        private void awaitBack(Fault fault, List<String> lost) throws InterruptedException {
            if (fault.connectionLevel) {
                await(
                        "the SDK reconnected",
                        RECONNECT,
                        () -> counters().get("transport.reconnects") >= expectedReconnects);
            }
            awaitNewSessionQueue(lost);
        }

        private void awaitNewSessionQueue(List<String> lost) throws InterruptedException {
            await("the session's queue declared anew", RECONNECT, () -> {
                var queues = broker.sessionQueues();
                return !queues.isEmpty() && queues.stream().noneMatch(lost::contains);
            });
        }

        /**
         * Every recovery of the live producer asked for since the fault begins at a time that is
         * given, and no later than the last message the session handled or alive it was sent.
         */
        private void assertRecoveryPoints(int liveBefore) throws InterruptedException {
            var requests = api.requests("POST", LIVE);
            assertThat(requests).as("recoveries asked for since the fault").hasSizeGreaterThan(liveBefore);
            for (int i = liveBefore; i < requests.size(); i++) {
                var request = requests.get(i);
                var id = request.parameter("request_id");
                // the fake records a request just before it calls the hook that notes the bound
                await("the bound noted for the recovery " + id, WAIT, () -> recoveryBounds.containsKey(id));
                var bound = recoveryBounds.get(id);
                assertThat(request.parameter("after"))
                        .as("where the recovery starts; none would mean a full snapshot: %s", request)
                        .isNotNull();
                assertThat(Long.parseLong(request.parameter("after")))
                        .as("where the recovery starts, against the last message handled when it was asked for")
                        .isPositive()
                        .isLessThanOrEqualTo(bound[0])
                        .isLessThanOrEqualTo(bound[1]);
            }
        }

        /**
         * A listener that closes the feed as the connection comes up, which is told on the events
         * thread, as the transport reconnects: the close returns, and the feed is gone.
         */
        private void closeAsTheConnectionComesUp() throws Exception {
            var closed = new CompletableFuture<Duration>();
            onUp.set(() -> closed.complete(StressFeed.timed(feed::close)));
            broker.closeConnections();
            assertThat(closed.get(RECONNECT.toSeconds(), TimeUnit.SECONDS))
                    .as("close() from the callback that is told the connection is up")
                    .isLessThan(SHUTDOWN.plus(MARGIN));
        }

        /** Whatever happened above, the load stops and the feed is closed. */
        void shutDown(Throwable failure) throws Exception {
            stopLoad.set(true);
            holdSnapshots.set(false);
            release(aliveGate);
            release(ledgerGate);
            readerThreads.forEach(Thread::interrupt);
            // every step runs, and a failure of the run itself is the one that is reported
            var cleanup = new StressFeed.Cleanup();
            var closeTook = new AtomicReference<Duration>();
            if (feed != null) {
                cleanup.step(() ->
                        closeTook.set(StressFeed.timedWithin(SHUTDOWN.plus(WAIT), "close() of the feed", feed::close)));
            }
            cleanup.step(health::close);
            cleanup.step(() -> {
                try {
                    deadlock.close();
                } catch (AssertionError found) {
                    System.err.println("the deadlock sentinel found: " + found.getMessage());
                }
            });
            for (var thread : threads) {
                cleanup.step(() -> thread.join(WAIT.toMillis()));
            }
            cleanup.step(() -> {
                if (closeTook.get() != null) {
                    assertThat(closeTook.get()).as("close() of the feed").isLessThan(SHUTDOWN.plus(MARGIN));
                }
            });
            cleanup.finish(failure);
        }

        void afterClose() {
            deadlock.close();
            assertThat(threads)
                    .as("threads of the load")
                    .allSatisfy(thread ->
                            assertThat(thread.isAlive()).as(thread.getName()).isFalse());
            assertThat(unexpected).as("failures of the load's threads").isEmpty();
            assertThat(readers)
                    .as("every reader made progress")
                    .allSatisfy(worker -> assertThat(worker.ticks()).isPositive());
            assertThat(fatal).as("fatal errors").isEmpty();
            health.assertNeverStalled();
        }

        // ------------------------------------------------------------------ the load

        private void startPublishers() {
            for (int j = 0; j < MATCHES; j++) {
                var match = "od:match:" + (9200 + j);
                spawn("stress-publisher-" + j, tick -> {
                    long period = Duration.ofSeconds(1).toNanos() / 12;
                    long next = System.nanoTime();
                    while (!stopLoad.get()) {
                        // a publisher holds the gate while it publishes: a cycle that takes it has them all idle
                        ledgerGate.readLock().lock();
                        try {
                            publishQuietly(current.get().ledger(), match);
                        } finally {
                            ledgerGate.readLock().unlock();
                        }
                        next += period;
                        pause(next);
                    }
                });
            }
        }

        /** Whether the broker routed it; a queue that is going away may refuse, and then it is not published. */
        private boolean publishQuietly(Ledger ledger, String match) {
            try {
                return ledger.publish(broker, match);
            } catch (RuntimeException e) {
                publishFailures.incrementAndGet();
                return false;
            }
        }

        /** An alive of both producers every second, and the snapshot that completes each recovery asked for. */
        private void startAlivesAndRecoveries() {
            spawn("stress-alives", tick -> {
                long next = System.nanoTime();
                while (!stopLoad.get()) {
                    aliveGate.readLock().lock();
                    try {
                        for (int producer : new int[] {1, 2}) {
                            long stamp = System.currentTimeMillis();
                            // noted first: a recovery asked for as the SDK handles it must not look ahead of it
                            lastAlive.accumulateAndGet(stamp, Math::max);
                            try {
                                broker.publishAsIs(FeedMessages.stampedAt(FeedMessages.alive(producer, true), stamp));
                            } catch (RuntimeException e) {
                                publishFailures.incrementAndGet();
                            }
                        }
                    } finally {
                        aliveGate.readLock().unlock();
                    }
                    next += Duration.ofSeconds(1).toNanos();
                    pause(next);
                }
            });
            spawn("stress-snapshots", tick -> {
                int[] done = new int[3];
                while (!stopLoad.get()) {
                    if (!holdSnapshots.get()) {
                        for (int producer : new int[] {1, 2}) {
                            var requests = api.requests("POST", producer == 1 ? PREMATCH : LIVE);
                            while (done[producer] < requests.size() && !holdSnapshots.get()) {
                                var requestId = Long.parseLong(
                                        requests.get(done[producer]).parameter("request_id"));
                                try {
                                    if (!broker.publish(FeedMessages.snapshotComplete(producer, requestId))) {
                                        break;
                                    }
                                } catch (RuntimeException e) {
                                    break;
                                }
                                snapshotsSent.incrementAndGet();
                                done[producer]++;
                            }
                        }
                    }
                    pause(System.nanoTime() + Duration.ofMillis(100).toNanos());
                }
            });
        }

        private void startReaders() {
            var matches = List.of(URN.parse("od:match:9200"), URN.parse("od:match:9201"));
            var competitors = List.of(URN.parse("od:competitor:47214"), URN.parse("od:competitor:47215"));
            for (int i = 0; i < READERS; i++) {
                var random = new SplittableRandom(Seed.derive(seed, "reader-" + i));
                spawn(
                        "stress-reader-" + i,
                        tick -> {
                            var sports = feed.getSportsInfoManager();
                            var producers = feed.getProducerManager();
                            while (!stopLoad.get() && !Thread.currentThread().isInterrupted()) {
                                try {
                                    switch (random.nextInt(5)) {
                                        case 0 -> sports.getMatch(matches.get(random.nextInt(matches.size())));
                                        case 1 ->
                                            sports.getCompetitor(competitors.get(random.nextInt(competitors.size())));
                                        case 2 -> sports.getSports();
                                        case 3 -> producers.isProducerDown(1 + random.nextInt(2));
                                        default -> feed.getHealth();
                                    }
                                } catch (OddsFeedSdkException allowed) {
                                    // the SDK's own, as a call may fail when the API does
                                } catch (RuntimeException e) {
                                    if (!Thread.currentThread().isInterrupted()) {
                                        unexpected.add("reader threw " + e);
                                    }
                                }
                                tick.run();
                            }
                        },
                        true);
            }
        }

        private void spawn(String name, Consumer<Runnable> body) {
            spawn(name, body, false);
        }

        private void spawn(String name, Consumer<Runnable> body, boolean reader) {
            // only a reader has to move all the time: the load's threads are held still by a cycle on purpose
            var worker = reader ? deadlock.worker(name) : null;
            var thread = new Thread(
                    () -> {
                        try {
                            body.accept(worker == null ? () -> {} : worker::tick);
                        } catch (Throwable e) {
                            unexpected.add(name + " died: " + e);
                        } finally {
                            if (worker != null) {
                                worker.done();
                            }
                        }
                    },
                    name);
            thread.setDaemon(true);
            threads.add(thread);
            if (reader) {
                readers.add(worker);
                readerThreads.add(thread);
            }
            thread.start();
        }

        // ------------------------------------------------------------------ small helpers

        private FeedHealth.Session session() {
            return feed.getHealth().sessions().stream()
                    .filter(session -> session.id() == sessionId)
                    .findFirst()
                    .orElseThrow();
        }

        private Map<String, Long> counters() {
            return feed.getHealth().counters();
        }

        private String counter(String name) {
            return "session." + sessionId + "." + name;
        }

        /** What a failure shows: every counter, and the last of the connection's states. */
        private String dump() {
            var seen = List.copyOf(states);
            var recent = seen.subList(Math.max(0, seen.size() - 8), seen.size());
            return "counters " + counters() + ", connection states " + recent + ", logins "
                    + broker.logins().size() + ", refused "
                    + broker.refusedLogins().size() + ", publishes failed " + publishFailures.get();
        }

        private void await(String what, Duration limit, BooleanSupplier ready) throws InterruptedException {
            long until = System.nanoTime() + limit.toNanos();
            while (!ready.getAsBoolean()) {
                if (System.nanoTime() >= until) {
                    throw new AssertionError(what + " not within " + limit + "; " + dump());
                }
                Thread.sleep(50);
            }
        }

        private static void eventually(Runnable check, Duration limit) throws InterruptedException {
            long until = System.nanoTime() + limit.toNanos();
            while (true) {
                try {
                    check.run();
                    return;
                } catch (AssertionError last) {
                    if (System.nanoTime() >= until) {
                        throw last;
                    }
                    Thread.sleep(100);
                }
            }
        }

        private static void release(ReentrantReadWriteLock gate) {
            if (gate.writeLock().isHeldByCurrentThread()) {
                gate.writeLock().unlock();
            }
        }

        private static void pause(long until) {
            long wait = until - System.nanoTime();
            if (wait > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // ------------------------------------------------------------------ the listeners

        private final class Listener implements StressFeed.OddsChanges {
            @Override
            public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
                lastDelivered.accumulateAndGet(message.getTimestamp().getCreated(), Math::max);
                current.get().recorder().onOddsChange(session, message);
            }
        }

        private final class Events implements StressFeed.QuietEvents {

            @Override
            public void onConnectionStateChange(ConnectionStateChange change) {
                states.add(change);
                if (change.state() == ConnectionState.UP) {
                    var close = onUp.getAndSet(null);
                    if (close != null) {
                        close.run();
                    }
                }
            }

            @Override
            public void onFatalError(String reason, Throwable cause) {
                fatal.add(reason);
            }

            @Override
            public void onHealthEvent(HealthEvent event) {
                health.onHealthEvent(event);
            }
        }
    }
}
