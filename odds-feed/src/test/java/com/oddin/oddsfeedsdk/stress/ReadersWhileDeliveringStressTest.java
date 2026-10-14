package com.oddin.oddsfeedsdk.stress;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.FeedHealth;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Readers hammer every accessor of the SDK while one session delivers from eight matches and two
 * threads keep invalidating the caches, which are too small to hold what is read. The API is faulty
 * all the while, and about one callback in a hundred throws.
 *
 * <p>What it must show: nothing deadlocks and every thread makes progress; the feed is never stalled;
 * no message of the ledger is lost, repeated or reordered, and the counters reconcile to the message;
 * readers fail only with the SDK's own exceptions; the REST calls stay within their permits; and a
 * closed feed leaves nothing behind. {@code -Dstress.seconds=N} sets the length of the hammering
 * (20 unless set), {@code -Dstress.seed=N} the seed, which a failure prints.
 *
 * <p>The exception strategy is the feed's, not a reader's, so the seed picks it: under {@code THROW}
 * a reader's failure must be an {@link OddsFeedSdkException}, under {@code CATCH} the getter returns
 * null, and either way anything else is a defect.
 */
@Tag("stress")
@ExtendWith(Seed.Report.class)
class ReadersWhileDeliveringStressTest {

    private static final int MATCHES = 8;
    private static final int READERS = 8;
    private static final int REST_LIMIT = 4;
    /** The producer, catalog and recovery pools beside the data one, as RestTransport has them. */
    private static final int OTHER_PERMITS = 2 + 2 + 2;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    /** A stalled body outlasts the HTTP timeout. */
    private static final Duration STALL = HTTP_TIMEOUT.plusSeconds(1);

    private static final Duration WAIT = Duration.ofSeconds(20);
    /** A call's deadline, two attempts and their margins, with room to spare: what "promptly" means. */
    private static final Duration PROMPTLY = Duration.ofSeconds(10);

    private static final Duration SHUTDOWN = Duration.ofSeconds(5);

    private static final List<URN> MATCH_IDS = IntStream.range(0, MATCHES)
            .mapToObj(i -> URN.parse("od:match:" + (9000 + i)))
            .toList();
    private static final List<URN> COMPETITOR_IDS =
            List.of(urn("competitor", 47214), urn("competitor", 47215), urn("competitor", 47216));
    private static final List<URN> PLAYER_IDS = List.of(urn("player", 1001), urn("player", 1002));
    private static final URN TOURNAMENT = urn("tournament", 1042);
    private static final URN SPORT = urn("sport", 23);

    private static final String COMPETITOR_PROFILE = "rest/competitor/competitor_profile.xml";

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
    void readersHammerEveryAccessorWhileSessionsDeliverAndCachesChurn() throws Exception {
        long seed = Seed.value();
        int seconds = Integer.getInteger("stress.seconds", 20);
        var strategy = (Seed.derive(seed, "strategy") & 1) == 0
                ? ExceptionHandlingStrategy.THROW
                : ExceptionHandlingStrategy.CATCH;
        System.out.printf("STRESS readers-while-delivering seed=%d seconds=%d strategy=%s%n", seed, seconds, strategy);

        var run = new Run(seed, strategy);
        var leaks = LeakCheck.begin().broker(broker);
        try (var api = FakeRestServer.start()) {
            run.api = api;
            leaks.rest(api);
            Throwable failure = null;
            try {
                run.execute(seconds);
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

    private static URN urn(String type, long id) {
        return URN.parse("od:" + type + ":" + id);
    }

    /** One run: the feed, its load, its readers, and what they found. */
    private static final class Run {

        private final long seed;
        private final ExceptionHandlingStrategy strategy;
        private final long base = System.currentTimeMillis();
        private final Ledger ledger = new Ledger(base);
        private final DeadlockSentinel deadlock = new DeadlockSentinel(Duration.ofSeconds(15));
        private final AtomicReference<OddsFeed> feedRef = new AtomicReference<>();
        private final HealthSentinel health =
                new HealthSentinel(() -> requireNonNull(feedRef.get()).getHealth());

        FakeRestServer api;

        private final AtomicBoolean stopLoad = new AtomicBoolean();
        private final AtomicBoolean stopReaders = new AtomicBoolean();
        private final List<Thread> loadThreads = new ArrayList<>();
        private final List<Thread> readerThreads = new ArrayList<>();
        private final List<Thread> probeThreads = new ArrayList<>();
        private final List<DeadlockSentinel.Worker> workers = new ArrayList<>();

        // what the threads found
        private final ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        private final Map<String, LongAdder> allowed = new ConcurrentHashMap<>();
        private final LongAdder reads = new LongAdder();
        private final LongAdder clears = new LongAdder();
        private final LongAdder sportClears = new LongAdder();

        // every message routed to the session, by kind: the session handles each exactly once
        private final AtomicLong alives = new AtomicLong();
        private final AtomicLong snapshots = new AtomicLong();
        private final AtomicLong fixtureChanges = new AtomicLong();
        private final AtomicLong fixtureChangesDelivered = new AtomicLong();
        private final AtomicLong unrouted = new AtomicLong();
        private final AtomicLong poisonPublished = new AtomicLong();
        private final AtomicLong poisonDelivered = new AtomicLong();

        // what the feed told its global listener
        private final AtomicLong connectionsDown = new AtomicLong();
        private final CopyOnWriteArrayList<String> fatal = new CopyOnWriteArrayList<>();

        private OddsFeed feed;
        private int sessionId;
        private int requestsBeforeChaos;
        private ChaosApi chaos;

        private final Map<String, Duration> probes = new ConcurrentHashMap<>();

        Run(long seed, ExceptionHandlingStrategy strategy) {
            this.seed = seed;
            this.strategy = strategy;
        }

        // ------------------------------------------------------------------ the scenario

        void execute(int seconds) throws Exception {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                    .setMessagingSslContext(TestTls.clientContext())
                    .setAccessToken("token")
                    .setExceptionHandlingStrategy(strategy)
                    // too small to hold what the readers read, so that entries are evicted all the time
                    .setMaxMatchCacheSize(4)
                    .setMaxFixtureCacheSize(4)
                    .setMaxCompetitorCacheSize(8)
                    .setMaxPlayerCacheSize(8)
                    .setRestConcurrencyLimit(REST_LIMIT)
                    .setHttpClientTimeout(HTTP_TIMEOUT)
                    // the ledger stamps sequences, not times: its messages grow old as a long run goes on
                    .setStaleMessageLimit(Duration.ofHours(24))
                    .build();
            feed = new OddsFeed(new Events(), configuration);
            feedRef.set(feed);
            var session = feed.getSessionBuilder()
                    .setListener(new Listener())
                    .setMessageInterest(MessageInterest.ALL)
                    .build();
            feed.open();
            deadlock.start();
            health.start();

            bringUp();
            sessionId = feed.getHealth().sessions().getFirst().id();
            assertThat(feed.getHealth().sessions().getFirst().session()).isSameAs(session);

            // before any fault: the paths whose outcome has to be exact
            probeCacheHit();
            probeCoalescedMiss();
            probeClearDuringLoad();
            probeEviction();

            requestsBeforeChaos = api.requests().size();
            chaos = new ChaosApi(seed, STALL);
            chaos.install(api);

            startLoad();
            startProbes();
            startReaders();

            boolean tripped = deadlock.awaitProblem(Duration.ofSeconds(seconds));
            if (tripped) {
                // the sentinel's own report comes out of close(), with the rest
                System.err.println("the deadlock sentinel tripped: " + deadlock.problem());
            }

            // stop the load, let everything it sent arrive, then the readers, interrupted halfway
            stopLoad.set(true);
            join(loadThreads, WAIT);
            join(probeThreads, WAIT);
            quiesce();
            stopReaders.set(true);
            for (int i = 1; i < readerThreads.size(); i += 2) {
                // a reader interrupted wherever it is: in a call, a wait for a permit, or between
                readerThreads.get(i).interrupt();
            }
            join(readerThreads, PROMPTLY.plus(PROMPTLY));

            check();
        }

        /**
         * Whatever happened above, the feed is closed, within its timeout, and the threads are gone. Every
         * step runs, and a failure of the run itself is the one that is reported.
         */
        void shutDown(Throwable failure) throws Exception {
            stopLoad.set(true);
            stopReaders.set(true);
            readerThreads.forEach(Thread::interrupt);
            var cleanup = new StressFeed.Cleanup();
            var closeTook = new AtomicReference<Duration>();
            if (feed != null) {
                cleanup.step(() ->
                        closeTook.set(StressFeed.timedWithin(SHUTDOWN.plus(WAIT), "close() of the feed", feed::close)));
            }
            cleanup.step(health::close);
            cleanup.step(() -> {
                try {
                    // afterClose() reports what it found; this only stops the polling
                    deadlock.close();
                } catch (AssertionError found) {
                    System.err.println("the deadlock sentinel found: " + found.getMessage());
                }
            });
            cleanup.step(() -> join(loadThreads, WAIT));
            cleanup.step(() -> join(probeThreads, WAIT));
            cleanup.step(() -> join(readerThreads, WAIT));
            cleanup.step(() -> {
                if (closeTook.get() != null) {
                    assertThat(closeTook.get()).as("close() of the feed").isLessThan(SHUTDOWN.plusSeconds(2));
                }
            });
            cleanup.finish(failure);
        }

        /** Once the feed is closed: the managers it handed out answer promptly, and the sentinels are clean. */
        void afterClose() {
            var sports = feed.getSportsInfoManager();
            var markets = feed.getMarketDescriptionManager();
            var producers = feed.getProducerManager();
            var recovery = feed.getRecoveryManager();
            promptly("sports.getSports", sports::getSports);
            promptly("sports.getMatch", () -> sports.getMatch(MATCH_IDS.getFirst()));
            promptly("sports.getLiveMatches", sports::getLiveMatches);
            promptly("sports.clearMatch", () -> sports.clearMatch(MATCH_IDS.getFirst()));
            promptly("markets.getMarketDescriptions", markets::getMarketDescriptions);
            promptly("markets.getMarketVoidReasons", markets::getMarketVoidReasons);
            promptly("producers.getAvailableProducers", producers::getAvailableProducers);
            promptly("producers.isProducerDown", () -> producers.isProducerDown(2));
            promptly("recovery.getEventRecoveryStatus", () -> recovery.getEventRecoveryStatus(1));
            promptly(
                    "recovery.initiateEventOddsMessagesRecovery",
                    () -> recovery.initiateEventOddsMessagesRecovery(2, MATCH_IDS.getFirst()));
            promptly("feed.getHealth", feed::getHealth);
            deadlock.close();
            assertThat(readerThreads)
                    .as("reader threads")
                    .allSatisfy(thread ->
                            assertThat(thread.isAlive()).as(thread.getName()).isFalse());
        }

        // ------------------------------------------------------------------ bring-up

        /** Producers 1 and 2 up, and the session bound, before anything counts. */
        private void bringUp() throws InterruptedException {
            long until = System.nanoTime() + WAIT.toNanos();
            // a fixture change is the probe of the session's binding: only its queue takes it
            while (!sendFixtureChange(MATCH_IDS.getFirst(), 1)) {
                assertThat(System.nanoTime())
                        .as("the session's queue bound within " + WAIT)
                        .isLessThan(until);
                Thread.sleep(100);
            }
            for (int producer : new int[] {1, 2}) {
                assertThat(sendAlive(producer))
                        .as("alive of producer %d routed", producer)
                        .isTrue();
                var path = (producer == 1 ? "/v1/pre" : "/v1/live") + "/recovery/initiate_request";
                var recovery = api.awaitRequest("POST", path);
                var complete =
                        FeedMessages.snapshotComplete(producer, Long.parseLong(recovery.parameter("request_id")));
                assertThat(broker.publish(complete)).isTrue();
                snapshots.incrementAndGet();
            }
            var producers = feed.getProducerManager();
            while (producers.isProducerDown(1) || producers.isProducerDown(2)) {
                assertThat(System.nanoTime()).as("producers up within " + WAIT).isLessThan(until);
                Thread.sleep(50);
            }
        }

        // ------------------------------------------------------------------ exit paths that must be exact

        /** A cache hit asks the API once. */
        private void probeCacheHit() throws Exception {
            var id = urn("competitor", 55601);
            var path = profilePath(id);
            var competitors = feed.getSportsInfoManager();
            for (int i = 0; i < 3; i++) {
                assertThat(requireNonNull(competitors.getCompetitor(id)).getName(Locale.ENGLISH))
                        .isNotBlank();
            }
            assertThat(api.requests("GET", path))
                    .as("requests for a competitor read three times")
                    .hasSize(1);
        }

        /** Readers that meet the same miss share one load. */
        private void probeCoalescedMiss() throws Exception {
            var id = urn("competitor", 55602);
            var path = profilePath(id);
            // the API holds the one load until every reader is waiting for it, and only then answers
            var allWaiting = new CountDownLatch(1);
            api.respondWith(request -> {
                if (request.path().equals(path)) {
                    try {
                        allWaiting.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Reply.of(200, Fixtures.read(COMPETITOR_PROFILE));
                }
                return null;
            });
            int readers = 6;
            var barrier = new CyclicBarrier(readers);
            var names = new ConcurrentLinkedQueue<String>();
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < readers; i++) {
                var thread = new Thread(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        names.add(requireNonNull(feed.getSportsInfoManager().getCompetitor(id))
                                .getName(Locale.ENGLISH));
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
                thread.start();
                threads.add(thread);
            }
            try {
                api.awaitRequest("GET", path);
                long until = System.nanoTime() + WAIT.toNanos();
                // none of them has finished, none is yet to begin: each is parked on the load, or in the call
                while (!threads.stream()
                                .allMatch(thread -> thread.getState() != Thread.State.NEW
                                        && thread.getState() != Thread.State.RUNNABLE
                                        && thread.getState() != Thread.State.TERMINATED)
                        && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
            } finally {
                allWaiting.countDown();
                api.respondWith(null);
            }
            join(threads, WAIT);
            assertThat(failures).as("failures of readers sharing a load").isEmpty();
            assertThat(names).hasSize(readers).doesNotContainNull();
            assertThat(api.requests("GET", path))
                    .as("requests for one competitor, six readers at once")
                    .hasSize(1);
        }

        /** A clear while a load runs: the load's result is thrown away, and the reader still gets a value. */
        private void probeClearDuringLoad() throws Exception {
            var id = urn("competitor", 55603);
            var path = profilePath(id);
            api.respond(path, Reply.of(200, Fixtures.read(COMPETITOR_PROFILE)).after(Duration.ofMillis(600)));
            long before = feed.getHealth().caches().staleFetchesDiscarded();
            var name = new AtomicReference<String>();
            var failure = new AtomicReference<Throwable>();
            var reader = new Thread(() -> {
                try {
                    name.set(requireNonNull(feed.getSportsInfoManager().getCompetitor(id))
                            .getName(Locale.ENGLISH));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            reader.start();
            api.awaitRequest("GET", path);
            feed.getSportsInfoManager().clearCompetitor(id);
            join(List.of(reader), WAIT);
            assertThat(failure.get()).as("the reader of a cleared load").isNull();
            assertThat(name.get()).isNotBlank();
            assertThat(feed.getHealth().caches().staleFetchesDiscarded())
                    .as("fetches discarded after the clear")
                    .isGreaterThan(before);
        }

        /** More competitors than the cache holds: reading them all again loads some of them again. */
        private void probeEviction() throws Exception {
            var ids = IntStream.range(0, 12)
                    .mapToObj(i -> urn("competitor", 56001 + i))
                    .toList();
            for (int round = 0; round < 2; round++) {
                for (var id : ids) {
                    assertThat(requireNonNull(feed.getSportsInfoManager().getCompetitor(id))
                                    .getName(Locale.ENGLISH))
                            .isNotBlank();
                }
            }
            long loads = ids.stream()
                    .mapToLong(id -> api.requests("GET", profilePath(id)).size())
                    .sum();
            // 8 fit, so at least 4 of the 12 were evicted and loaded again by the second round
            assertThat(loads)
                    .as("loads of 12 competitors read twice, with room for 8")
                    .isGreaterThanOrEqualTo(16);
        }

        // ------------------------------------------------------------------ threads

        private void startLoad() {
            for (int i = 0; i < MATCHES; i++) {
                var match = MATCH_IDS.get(i).toString();
                spawn(loadThreads, "stress-publisher-" + i, tick -> {
                    long period = Duration.ofSeconds(1).toNanos() / 15;
                    long next = System.nanoTime();
                    while (!stopLoad.get()) {
                        long sequence = ledger.mark().getOrDefault(match, 0L);
                        if (ledger.publish(broker, match)) {
                            if (poisoned(seed, match, sequence)) {
                                poisonPublished.incrementAndGet();
                            }
                        } else {
                            unrouted.incrementAndGet();
                        }
                        tick.run();
                        next += period;
                        pause(next);
                    }
                });
            }
            spawn(loadThreads, "stress-alives", tick -> {
                long next = System.nanoTime();
                while (!stopLoad.get()) {
                    sendAlive(1);
                    sendAlive(2);
                    tick.run();
                    next += Duration.ofSeconds(1).toNanos();
                    pause(next);
                }
            });
            // the fixture changes that invalidate, among the messages the session handles
            spawn(loadThreads, "stress-invalidator-fixture-changes", tick -> {
                var random = new SplittableRandom(Seed.derive(seed, "invalidator-fixture-changes"));
                long next = System.nanoTime();
                while (!stopLoad.get()) {
                    sendFixtureChange(MATCH_IDS.get(random.nextInt(MATCHES)), 1 + random.nextInt(5));
                    tick.run();
                    next += Duration.ofMillis(50).toNanos();
                    pause(next);
                }
            });
            // the clears of every kind
            spawn(loadThreads, "stress-invalidator-clears", tick -> {
                var random = new SplittableRandom(Seed.derive(seed, "invalidator-clears"));
                var sports = feed.getSportsInfoManager();
                var markets = feed.getMarketDescriptionManager();
                long next = System.nanoTime();
                while (!stopLoad.get()) {
                    attempt("clear", () -> {
                        switch (random.nextInt(9)) {
                            case 0, 1 -> sports.clearMatch(pick(random, MATCH_IDS));
                            case 2 -> sports.clearTournament(TOURNAMENT);
                            case 7 -> {
                                sports.clearSport(SPORT);
                                sportClears.increment();
                            }
                            case 3, 4 -> sports.clearCompetitor(pick(random, COMPETITOR_IDS));
                            case 5 -> sports.clearPlayer(pick(random, PLAYER_IDS));
                            case 6 -> markets.clearMarketDescription(1, random.nextBoolean() ? null : "way:two");
                            default -> markets.clearMarketVoidReasons();
                        }
                        clears.increment();
                    });
                    tick.run();
                    next += Duration.ofMillis(20).toNanos();
                    pause(next);
                }
            });
        }

        /** The calls that fail: 5xx, a body cut off, a body that stalls past the HTTP timeout. */
        private void startProbes() {
            record Probe(String name, long id, Reply reply) {}
            var body = Fixtures.read(COMPETITOR_PROFILE);
            for (var probe : List.of(
                    new Probe(
                            "server-error", 55604, Reply.of(503, "<response response_code=\"SERVICE_UNAVAILABLE\"/>")),
                    new Probe("cut-off", 55605, Reply.of(200, body).cutOffMidBody()),
                    new Probe("stall", 55606, Reply.of(200, body).stallingMidBody(STALL)))) {
                var id = urn("competitor", probe.id());
                api.respond(profilePath(id), probe.reply());
                spawn(probeThreads, "stress-probe-" + probe.name(), tick -> {
                    long started = System.nanoTime();
                    Supplier<String> read = () -> {
                        var competitor = feed.getSportsInfoManager().getCompetitor(id);
                        return competitor == null ? null : competitor.getName(Locale.ENGLISH);
                    };
                    try {
                        var name = read.get();
                        // CATCH answers null; THROW must not have a name to give
                        assertThat(strategy)
                                .as("%s: a name, though the API failed", probe.name())
                                .isEqualTo(ExceptionHandlingStrategy.CATCH);
                        assertThat(name).as("%s under CATCH", probe.name()).isNull();
                    } catch (OddsFeedSdkException e) {
                        assertThat(strategy)
                                .as("%s: threw under CATCH", probe.name())
                                .isEqualTo(ExceptionHandlingStrategy.THROW);
                        // the documented outcome of a read the API failed, not any exception of the SDK's
                        assertThat(e).as("%s: what it threw", probe.name()).isInstanceOf(ItemNotFoundException.class);
                    }
                    probes.put(probe.name(), Duration.ofNanos(System.nanoTime() - started));
                    tick.run();
                });
            }
        }

        private void startReaders() {
            for (int i = 0; i < READERS; i++) {
                int index = i;
                spawn(readerThreads, "stress-reader-" + i, tick -> {
                    var reader = new Reader(new SplittableRandom(Seed.derive(seed, "reader-" + index)));
                    while (!stopReaders.get() && !Thread.currentThread().isInterrupted()) {
                        reader.step();
                        tick.run();
                    }
                });
            }
        }

        /** A reader: random calls on every manager, and on whatever entities its calls have returned. */
        private final class Reader {

            private final SplittableRandom random;
            private final List<Object> targets = new ArrayList<>();
            private final List<Runnable> managerCalls;

            Reader(SplittableRandom random) {
                this.random = random;
                var sports = feed.getSportsInfoManager();
                var markets = feed.getMarketDescriptionManager();
                var producers = feed.getProducerManager();
                var recovery = feed.getRecoveryManager();
                var calls = new ArrayList<Runnable>();
                call(calls, "sports.getSports", () -> absorb(sports.getSports()));
                call(calls, "sports.getActiveTournaments", () -> absorb(sports.getActiveTournaments()));
                call(
                        calls,
                        "sports.getActiveTournaments(sport)",
                        () -> absorb(sports.getActiveTournaments("PenaltyArena")));
                call(calls, "sports.getMatchesFor", () -> absorb(sports.getMatchesFor(new Date())));
                call(calls, "sports.getLiveMatches", () -> absorb(sports.getLiveMatches()));
                call(calls, "sports.getFixtureChanges", () -> absorb(sports.getFixtureChanges()));
                call(calls, "sports.getListOfMatches", () -> absorb(sports.getListOfMatches(0, 20)));
                call(calls, "sports.getAvailableTournaments", () -> absorb(sports.getAvailableTournaments(SPORT)));
                call(calls, "sports.getSport", () -> absorb(sports.getSport(SPORT)));
                call(calls, "sports.getTournament", () -> absorb(sports.getTournament(TOURNAMENT)));
                call(calls, "sports.getPlayer", () -> absorb(sports.getPlayer(pick(random, PLAYER_IDS))));
                call(calls, "markets.getMarketDescriptions", () -> absorb(markets.getMarketDescriptions()));
                call(
                        calls,
                        "markets.getMarketDescription",
                        () -> absorb(markets.getMarketDescription(1, null, Locale.ENGLISH)));
                call(
                        calls,
                        "markets.getMarketDescription(variant)",
                        () -> absorb(markets.getMarketDescription(1, "way:two", Locale.ENGLISH)));
                call(calls, "markets.getMarketVoidReasons", () -> absorb(markets.getMarketVoidReasons()));
                call(calls, "producers.getAvailableProducers", () -> absorb(producers.getAvailableProducers()));
                call(calls, "producers.getActiveProducers", () -> absorb(producers.getActiveProducers()));
                call(calls, "producers.getProducer", () -> absorb(producers.getProducer(1 + random.nextInt(2))));
                call(calls, "producers.isProducerEnabled", () -> producers.isProducerEnabled(1 + random.nextInt(2)));
                call(calls, "producers.isProducerDown", () -> producers.isProducerDown(1 + random.nextInt(2)));
                call(
                        calls,
                        "recovery.getEventRecoveryStatus",
                        () -> absorb(recovery.getEventRecoveryStatus(random.nextInt(5))));
                call(calls, "feed.getHealth", () -> absorb(feed.getHealth().counters()));
                managerCalls = calls;
            }

            private void call(List<Runnable> calls, String name, Runnable call) {
                calls.add(() -> attempt(name, call));
            }

            void step() {
                int roll = random.nextInt(20);
                if (roll < 12) {
                    walk();
                } else if (roll < 19) {
                    managerCalls.get(random.nextInt(managerCalls.size())).run();
                } else {
                    // a POST: the recovery of one event
                    attempt(
                            "recovery.initiateEventOddsMessagesRecovery",
                            () -> feed.getRecoveryManager()
                                    .initiateEventOddsMessagesRecovery(2, pick(random, MATCH_IDS)));
                }
            }

            /** One getter of one entity: the table of getters, on what the readers have found. */
            private void walk() {
                if (targets.isEmpty() || random.nextInt(6) == 0) {
                    attempt("entity", () -> {
                        var sports = feed.getSportsInfoManager();
                        absorb(
                                switch (random.nextInt(5)) {
                                    case 0, 1 -> sports.getMatch(pick(random, MATCH_IDS));
                                    case 2 -> sports.getCompetitor(pick(random, COMPETITOR_IDS));
                                    case 3 -> sports.getPlayer(pick(random, PLAYER_IDS));
                                    default -> sports.getTournament(TOURNAMENT);
                                });
                    });
                }
                if (targets.isEmpty()) {
                    return;
                }
                var target = targets.get(random.nextInt(targets.size()));
                var accessors = AccessorTable.of(target);
                if (accessors.isEmpty()) {
                    targets.remove(target);
                    return;
                }
                var accessor = accessors.get(random.nextInt(accessors.size()));
                attempt(accessor.name(), () -> {
                    try {
                        absorb(accessor.call());
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }

            /** Keeps what a call returned that the table has getters for, to call them next. */
            private void absorb(Object result) {
                if (result == null) {
                    return;
                }
                if (!AccessorTable.of(result).isEmpty()) {
                    keep(result);
                } else if (result instanceof Iterable<?> items) {
                    int kept = 0;
                    for (var item : items) {
                        if (item != null && kept++ < 3) {
                            absorb(item);
                        }
                    }
                } else if (result instanceof Map<?, ?> map) {
                    map.values().stream().limit(3).forEach(this::absorb);
                }
            }

            private void keep(Object entity) {
                if (targets.size() >= 32) {
                    targets.remove(random.nextInt(targets.size()));
                }
                targets.add(entity);
            }
        }

        // ------------------------------------------------------------------ what the threads record

        /** Runs one call of a reader, which may fail only as the SDK's own exceptions do. */
        private void attempt(String what, Runnable call) {
            try {
                call.run();
                reads.increment();
            } catch (OddsFeedSdkException e) {
                allowed.computeIfAbsent(e.getClass().getSimpleName(), _ -> new LongAdder())
                        .increment();
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted() && cause(e) instanceof InterruptedException) {
                    // a call that an interrupt ended is the thread being told to stop
                    return;
                }
                // a hot exception of the JVM's own may come with no stack trace at all
                var trace = e.getStackTrace();
                unexpected.add(what + " threw " + e + " at " + (trace.length > 0 ? trace[0] : "(no stack trace)"));
            }
        }

        private static Throwable cause(Throwable e) {
            while (e.getCause() != null) {
                e = e.getCause();
            }
            return e;
        }

        /** Starts a thread of the run; its failures are findings, and it is done when it ends. */
        private void spawn(List<Thread> into, String name, java.util.function.Consumer<Runnable> body) {
            var worker = deadlock.worker(name);
            workers.add(worker);
            var thread = new Thread(
                    () -> {
                        try {
                            body.accept(worker::tick);
                        } catch (Throwable e) {
                            unexpected.add(name + " died: " + e);
                        } finally {
                            worker.done();
                        }
                    },
                    name);
            thread.setDaemon(true);
            into.add(thread);
            thread.start();
        }

        private void promptly(String what, Runnable call) {
            // a call that never returns fails here, at its deadline, not at the end of the job
            var took = StressFeed.timedWithin(PROMPTLY, what + " on a closed feed", () -> {
                try {
                    call.run();
                } catch (OddsFeedSdkException e) {
                    // a closed feed may refuse, in the SDK's own words
                }
            });
            assertThat(took).as("%s on a closed feed", what).isLessThan(PROMPTLY);
        }

        // ------------------------------------------------------------------ traffic

        private boolean sendAlive(int producer) {
            boolean routed = broker.publish(FeedMessages.alive(producer, true));
            if (routed) {
                alives.incrementAndGet();
            }
            return routed;
        }

        private boolean sendFixtureChange(URN match, int changeType) {
            // the broker stamps each with the time it is published: the SDK drops a change of an event and
            // time it has handled already, and these are 50 ms apart
            boolean routed = broker.publish(StressFeed.fixtureChange(match.toString(), changeType));
            if (routed) {
                fixtureChanges.incrementAndGet();
            }
            return routed;
        }

        // ------------------------------------------------------------------ the end of the run

        /** Everything that was sent has arrived and been handled. */
        private void quiesce() throws InterruptedException {
            long expected = ledger.publishedCount() + alives.get() + snapshots.get() + fixtureChanges.get();
            long until = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < until) {
                var session = session();
                if (ledger.deliveredCount() >= ledger.publishedCount()
                        && session.handled() >= expected
                        && session.queueDepth() == 0) {
                    return;
                }
                Thread.sleep(20);
            }
            // the checks that follow say what is missing
        }

        private FeedHealth.Session session() {
            return feed.getHealth().sessions().stream()
                    .filter(session -> session.id() == sessionId)
                    .findFirst()
                    .orElseThrow();
        }

        private void check() throws Exception {
            var counters = feed.getHealth().counters();
            long routed = ledger.publishedCount() + alives.get() + snapshots.get() + fixtureChanges.get();
            var dump =
                    "counters %s, routed: odds %d, alives %d, snapshots %d, fixture changes %d; allowed failures %s; probes %s"
                            .formatted(
                                    counters,
                                    ledger.publishedCount(),
                                    alives.get(),
                                    snapshots.get(),
                                    fixtureChanges.get(),
                                    allowed,
                                    probes);
            System.out.println("STRESS " + dump);
            String prefix = "session." + sessionId + ".";

            assertThat(deadlock.problem()).as("the deadlock sentinel").isNull();
            health.assertNeverStalled();
            assertThat(unexpected)
                    .as("failures that are not the SDK's own, or dead threads")
                    .isEmpty();
            assertThat(workers)
                    .as("every worker made progress")
                    .allSatisfy(worker -> assertThat(worker.ticks()).isPositive());
            assertThat(reads.sum()).as("reads that succeeded").isPositive();

            // the ledger, then the counters to the message
            assertThat(unrouted.get())
                    .as("odds changes the broker did not route")
                    .isZero();
            ledger.assertExact();
            assertThat(ledger.publishedCount()).as("odds changes published").isPositive();
            assertThat(counters.get(prefix + "handled"))
                    .as("handled, against %s", dump)
                    .isEqualTo(routed);
            assertThat(counters.get(prefix + "queue_depth")).isZero();
            for (var name : List.of(
                    "queue_overflows",
                    "epoch_discards",
                    "skipped_acks",
                    "unparsable",
                    "oversized",
                    "unknown_producers",
                    "pipeline_failures")) {
                assertThat(counters.get(prefix + name))
                        .as(name + ", against %s", dump)
                        .isZero();
            }
            assertThat(poisonDelivered.get()).as("poisoned callbacks").isEqualTo(poisonPublished.get());
            assertThat(poisonPublished.get()).as("poisoned messages published").isPositive();
            assertThat(counters.get(prefix + "callback_failures"))
                    .as("callback_failures, against %s", dump)
                    .isEqualTo(poisonPublished.get());
            assertThat(counters.get("events.callback_failures"))
                    .as("the global listener's failures")
                    .isZero();
            assertThat(counters.get("transport.reconnects")).as("reconnects").isZero();
            assertThat(connectionsDown.get()).as("onConnectionDown").isZero();
            assertThat(fatal).as("fatal errors").isEmpty();

            // eviction and invalidation happened
            assertThat(clears.sum()).as("clears").isPositive();
            assertThat(sportClears.sum()).as("clears of the sport").isPositive();
            assertThat(fixtureChanges.get()).as("fixture changes sent").isGreaterThan(1);
            // none dropped as a repeat of one handled: each came with a time of its own, so each reached the listener
            assertThat(fixtureChangesDelivered.get())
                    .as("fixture changes delivered, of those sent")
                    .isEqualTo(fixtureChanges.get());

            // The REST calls stayed within their permits. The fake counts a request until its answer
            // ends, and a stalled one outlives the client's deadline, which gave its permit back: so
            // each stall that began within the time a stall outlasts that deadline - with a second of
            // margin - may add one, and the stall probe's.
            int abandoned = 1 + chaos.mostStallsWithin(STALL.minus(HTTP_TIMEOUT).plusSeconds(1));
            assertThat(api.mostInFlight())
                    .as(
                            "most REST requests in flight, of %d data permits, %d others, and up to %d abandoned stalls",
                            REST_LIMIT, OTHER_PERMITS, abandoned)
                    .isLessThanOrEqualTo(REST_LIMIT + OTHER_PERMITS + abandoned);
            assertThat(api.requests().size() - requestsBeforeChaos)
                    .as("requests under the chaos")
                    .isPositive();
            assertThat(probes).as("the failing calls ended").containsKeys("server-error", "cut-off", "stall");
            probes.forEach((name, took) ->
                    assertThat(took).as("the %s call, until it failed", name).isLessThan(PROMPTLY));

            // nothing the broker handed out is left unacknowledged
            long until = System.nanoTime() + WAIT.toNanos();
            long open = broker.unacknowledged();
            while (open != 0 && System.nanoTime() < until) {
                Thread.sleep(200);
                open = broker.unacknowledged();
            }
            assertThat(open).as("unacknowledged before close").isZero();
        }

        // ------------------------------------------------------------------ small helpers

        private static String profilePath(URN competitor) {
            return "/v1/sports/en/competitors/" + competitor + "/profile";
        }

        private static <T> T pick(SplittableRandom random, List<T> from) {
            return from.get(random.nextInt(from.size()));
        }

        /**
         * About one message in a hundred, by the seed and the message alone, and always the third of the
         * first match: a run too short for the seed to pick one still has a poisoned callback to count.
         */
        private static boolean poisoned(long seed, String match, long sequence) {
            return (sequence == 2 && match.equals(MATCH_IDS.getFirst().toString()))
                    || Math.floorMod(Seed.mix(seed ^ Seed.mix(match.hashCode() * 1_000_003L + sequence)), 100) == 0;
        }

        /** Sleeps until the instant, as System.nanoTime reads it. */
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

        private static void join(Collection<Thread> threads, Duration limit) throws InterruptedException {
            long until = System.nanoTime() + limit.toNanos();
            for (var thread : threads) {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
                assertThat(thread.isAlive())
                        .as("%s ended within %s", thread.getName(), limit)
                        .isFalse();
            }
        }

        // ------------------------------------------------------------------ the listeners

        /** The ledger's recorder, with a callback that throws for the messages the seed poisons. */
        private final class Listener implements OddsFeedListener {

            private final OddsFeedListener recorder = ledger.listener();

            @Override
            public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
                recorder.onOddsChange(session, message);
                var match = message.getEvent().getId().toString();
                if (poisoned(seed, match, message.getTimestamp().getCreated() - base)) {
                    poisonDelivered.incrementAndGet();
                    throw new IllegalStateException("poisoned callback, as a client's can be");
                }
            }

            @Override
            public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {}

            @Override
            public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {}

            @Override
            public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {}

            @Override
            public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {}

            @Override
            public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {}

            @Override
            public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
                fixtureChangesDelivered.incrementAndGet();
            }
        }

        private final class Events implements GlobalEventsListener {

            @Override
            public void onProducerStatusChange(ProducerStatus producerStatus) {}

            @Override
            public void onConnectionDown() {
                connectionsDown.incrementAndGet();
            }

            @Override
            public void onEventRecoveryCompleted(URN eventId, long requestId) {}

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
