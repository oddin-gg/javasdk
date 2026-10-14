package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.exceptions.InitException;
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
import com.oddin.oddsfeedsdk.subscribe.ConnectionState;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Builds, opens, loads and closes a feed fifteen times over, each time ended by a close from another
 * place: a publisher's thread, a session's callback, the events callback, a virtual thread racing
 * {@code open()}, two threads closing at once, or the thread of a reader blocked on a slow API call.
 * The seed decides where the rotation starts and when in the burst each close comes.
 *
 * <p>After every cycle, once the close that began it has returned: it took no longer than the
 * shutdown timeout and a margin; the feed's threads are gone, the broker has no connection and no
 * client queue of the feed's, and the REST fake is quiet; nothing is left unacknowledged; no listener
 * call began after the close returned; what each match was delivered is a gapless start of what was
 * published; and the health never read STALLED. {@code -Dstress.seconds=N} scales the burst of each
 * cycle (20 unless set), {@code -Dstress.seed=N} sets the seed, which a failure prints.
 */
@Tag("stress")
@ExtendWith(Seed.Report.class)
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LifecycleLoopStressTest {

    private static final int CYCLES = 15;
    private static final int MATCHES = 3;
    /** The configuration's, which this scenario leaves at its default. */
    private static final Duration SHUTDOWN = Duration.ofSeconds(5);

    private static final Duration MARGIN = Duration.ofSeconds(2);
    private static final Duration WAIT = Duration.ofSeconds(20);
    /** What a reader blocked in a call may take to return once the feed is closed. */
    private static final Duration PROMPTLY = Duration.ofSeconds(5);

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final URN SLOW_COMPETITOR = URN.parse("od:competitor:55701");

    private enum Source {
        PUBLISHER,
        SESSION_CALLBACK,
        EVENTS_CALLBACK,
        RACING_OPEN,
        DOUBLE_CLOSE,
        READER_BLOCKED
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
    void aFeedOpenedLoadedAndClosedFromEverySourceLeavesNothingBehind() throws Exception {
        long seed = Seed.value();
        int seconds = Integer.getInteger("stress.seconds", 20);
        int burst = Math.max(10, 60 * seconds / 20);
        int rotation = Math.floorMod(Seed.derive(seed, "sources"), Source.values().length);
        System.out.printf("STRESS lifecycle-loop seed=%d cycles=%d burst=%d%n", seed, CYCLES, burst);

        var deadlock = new DeadlockSentinel(Duration.ofSeconds(15)).start();
        long delivered = 0;
        try (var api = FakeRestServer.start()) {
            for (int i = 0; i < CYCLES; i++) {
                var source = Source.values()[(i + rotation) % Source.values().length];
                var cycle = new Cycle(api, seed, i, source, burst);
                try {
                    cycle.run();
                } finally {
                    delivered += cycle.ledger.deliveredCount();
                }
            }
        } finally {
            deadlock.close();
        }
        assertThat(delivered)
                .as("odds changes delivered over %d cycles", CYCLES)
                .isPositive();
    }

    /** One feed, from its build to the end of its close. */
    private static final class Cycle {

        private final FakeRestServer api;
        private final int index;
        private final Source source;
        private final int burst;
        private final SplittableRandom random;
        /** The close of the source, after this many messages of its kind. */
        private final int closeAfter;

        private final Ledger ledger = new Ledger(System.currentTimeMillis());
        private final OddsFeedSessionListener listener = new OddsFeedSessionListener();
        private final HealthSentinel health;
        private final AtomicBoolean closeReturned = new AtomicBoolean();
        private final ConcurrentLinkedQueue<String> lateCalls = new ConcurrentLinkedQueue<>();
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicBoolean closeOnce = new AtomicBoolean();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicInteger publishFailures = new AtomicInteger();
        private final LongAccumulator slowestClose = new LongAccumulator(Math::max, 0);
        private final CountDownLatch sourceClosed = new CountDownLatch(1);
        /** What the API holds the blocked reader's call on, until the cycle lets it go. */
        private final CountDownLatch heldCall = new CountDownLatch(1);

        private final List<Thread> threads = new ArrayList<>();
        private volatile OddsFeed feed;

        Cycle(FakeRestServer api, long seed, int index, Source source, int burst) {
            this.api = api;
            this.index = index;
            this.source = source;
            this.burst = burst;
            this.random = new SplittableRandom(Seed.derive(seed, "cycle-" + index));
            this.closeAfter = 5 + random.nextInt(Math.max(1, burst / 2));
            this.health = new HealthSentinel(() -> feed.getHealth());
        }

        void run() throws Exception {
            System.out.printf("STRESS cycle %d: close from %s after %d%n", index, source, closeAfter);
            var leaks = LeakCheck.begin().broker(broker).rest(api);
            var racingOn = random.nextBoolean() ? ConnectionState.CONNECTING : ConnectionState.UP;
            feed = StressFeed.feed(new Events(racingOn), broker, api, builder -> builder);
            feed.getSessionBuilder()
                    .setListener(listener)
                    .setMessageInterest(MessageInterest.ALL)
                    .build();
            try {
                open();
                health.start();
                if (source != Source.RACING_OPEN) {
                    StressFeed.awaitRouted(
                            () -> sourceClosed.getCount() == 0
                                    || broker.publish(StressFeed.fixtureChange("od:match:9100", 1)),
                            "the probe",
                            WAIT);
                }
                var blockedReader = source == Source.READER_BLOCKED ? blockReader() : null;
                startBurst();
                switch (source) {
                    case EVENTS_CALLBACK -> bringProducerUp();
                    case DOUBLE_CLOSE -> {
                        awaitPublished(closeAfter);
                        closeTwice();
                    }
                    case READER_BLOCKED -> {
                        awaitPublished(closeAfter);
                        assertThat(blockedReader.isAlive())
                                .as("the reader, still held in its API call when the close begins")
                                .isTrue();
                        closeAndMark();
                        // the API still holds the call: only the close can have ended it
                        blockedReader.join(PROMPTLY.toMillis());
                        assertThat(blockedReader.isAlive())
                                .as("the reader blocked in an API call, within %s of the close", PROMPTLY)
                                .isFalse();
                    }
                    default -> {
                        // the source closes the feed itself
                    }
                }
                assertThat(sourceClosed.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                        .as("the close of %s came", source)
                        .isTrue();
            } finally {
                stop.set(true);
                // a reader the cycle gave up on is let go, whatever the close did
                heldCall.countDown();
                api.respondWith(null);
                // a no-op once the source has closed: only a cycle that failed before it needs this
                feed.close();
                for (var thread : threads) {
                    thread.join(WAIT.toMillis());
                }
            }
            for (var thread : threads) {
                assertThat(thread.isAlive()).as(thread.getName()).isFalse();
            }
            health.close();

            // what the cycle must have left, now that the close that began it has returned
            assertThat(Duration.ofNanos(slowestClose.get()))
                    .as("the slowest close() of the feed")
                    .isLessThan(SHUTDOWN.plus(MARGIN));
            leaks.assertClean(WAIT);
            assertThat(broker.unacknowledged()).as("unacknowledged after close").isZero();
            assertThat(lateCalls)
                    .as("listener calls begun after close() returned")
                    .isEmpty();
            ledger.assertPrefix();
            health.assertNeverStalled();
            System.out.printf(
                    "STRESS cycle %d: %d of %d odds changes delivered, %d publishes failed%n",
                    index, ledger.deliveredCount(), ledger.publishedCount(), publishFailures.get());
        }

        private void open() {
            if (source != Source.RACING_OPEN) {
                feed.open();
                return;
            }
            try {
                feed.open();
            } catch (InitException closedFirst) {
                // the close won: the SDK allows either outcome
                assertThat(closedFirst).hasMessageContaining("closed");
            }
        }

        /**
         * A reader of the SDK's whose call the API holds, until the cycle lets it go at its end: so
         * the call can only return in time because the feed's close ended it.
         */
        private Thread blockReader() throws InterruptedException {
            var path = "/v1/sports/en/competitors/" + SLOW_COMPETITOR + "/profile";
            var inCall = new CountDownLatch(1);
            api.respond(path, Reply.of(200, Fixtures.read("rest/competitor/competitor_profile.xml")));
            api.respondWith(request -> {
                if (request.path().equals(path)) {
                    inCall.countDown();
                    try {
                        heldCall.await(2 * WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            });
            var reader = thread("stress-blocked-reader", () -> {
                try {
                    feed.getSportsInfoManager().getCompetitor(SLOW_COMPETITOR).getName(Locale.ENGLISH);
                } catch (RuntimeException closedOrFailed) {
                    // either answer is the SDK's: what matters is that the call returns
                }
            });
            assertThat(inCall.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .as("the reader's call reached the API")
                    .isTrue();
            return reader;
        }

        private void startBurst() {
            for (int j = 0; j < MATCHES; j++) {
                var match = "od:match:" + (9100 + j);
                boolean closes = source == Source.PUBLISHER && j == 0;
                thread("stress-publisher-" + j, () -> {
                    for (int n = 0; n < burst && !stop.get(); n++) {
                        try {
                            ledger.publish(broker, match);
                        } catch (RuntimeException e) {
                            // a queue the close is taking down may refuse: the message is not published
                            publishFailures.incrementAndGet();
                        }
                        if (closes && n == closeAfter) {
                            closeAndMark();
                        }
                    }
                });
            }
        }

        /** The producer coming up, as its recovery completes, is told to the events callback, which closes. */
        private void bringProducerUp() throws InterruptedException {
            // the fake serves every cycle: this cycle's recovery is the one after those of the cycles before
            int recoveriesBefore = api.requests("POST", PREMATCH_RECOVERY).size();
            // the callback may close the feed before any of this: then there is nothing more to send
            if (!broker.publish(FeedMessages.alive(1, true)) && sourceClosed.getCount() == 0) {
                return;
            }
            RecordedRequest recovery;
            try {
                recovery = api.awaitRequests("POST", PREMATCH_RECOVERY, recoveriesBefore + 1)
                        .get(recoveriesBefore);
            } catch (AssertionError none) {
                if (sourceClosed.getCount() == 0) {
                    return;
                }
                throw none;
            }
            var complete = FeedMessages.snapshotComplete(1, Long.parseLong(recovery.parameter("request_id")));
            StressFeed.awaitRouted(
                    () -> sourceClosed.getCount() == 0 || broker.publish(complete), "the snapshot", WAIT);
        }

        private void awaitPublished(int count) throws InterruptedException {
            long until = System.nanoTime() + WAIT.toNanos();
            while (ledger.publishedCount() < count && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(ledger.publishedCount()).as("published before the close").isGreaterThanOrEqualTo(count);
        }

        // ------------------------------------------------------------------ the closes

        /** The close of the source: the feed's, timed, and then the cycle may begin to check. */
        private void closeAndMark() {
            if (!closeOnce.compareAndSet(false, true)) {
                return;
            }
            slowestClose.accumulate(StressFeed.timed(feed::close).toNanos());
            closeReturned.set(true);
            sourceClosed.countDown();
        }

        /** Two threads close at once; the cycle checks once both have returned, as the second may return at once. */
        private void closeTwice() throws Exception {
            var together = new CyclicBarrier(2);
            var closers = new ArrayList<Thread>();
            var failures = new ConcurrentLinkedQueue<String>();
            for (int i = 0; i < 2; i++) {
                var name = "stress-closer-" + i;
                var closer = new Thread(
                        () -> {
                            try {
                                together.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                                slowestClose.accumulate(
                                        StressFeed.timed(feed::close).toNanos());
                            } catch (Throwable e) {
                                failures.add(name + ": " + e);
                            }
                        },
                        name);
                closer.setDaemon(true);
                closers.add(closer);
                closer.start();
            }
            for (var closer : closers) {
                closer.join(WAIT.toMillis());
                assertThat(closer.isAlive()).as(closer.getName()).isFalse();
            }
            assertThat(failures)
                    .as("closes of the feed that did not return normally")
                    .isEmpty();
            closeReturned.set(true);
            sourceClosed.countDown();
        }

        private Thread thread(String name, Runnable body) {
            var thread = new Thread(body, name);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
            return thread;
        }

        // ------------------------------------------------------------------ the listeners

        /** A call of any listener that begins once the close has returned is noted. */
        private void late(String callback) {
            if (closeReturned.get()) {
                lateCalls.add(callback);
            }
        }

        /** The ledger's recorder, which notes every call that begins once the close has returned. */
        private final class OddsFeedSessionListener implements StressFeed.OddsChanges {

            private final OddsFeedListener recorder = ledger.listener();

            @Override
            public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
                late("onOddsChange");
                recorder.onOddsChange(session, message);
                if (source == Source.SESSION_CALLBACK && delivered.incrementAndGet() == closeAfter) {
                    closeAndMark();
                }
            }

            @Override
            public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
                late("onBetStop");
            }

            @Override
            public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
                late("onBetSettlement");
            }

            @Override
            public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
                late("onRollbackBetSettlement");
            }

            @Override
            public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
                late("onRollbackBetCancel");
            }

            @Override
            public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
                late("onBetCancel");
            }

            @Override
            public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
                late("onFixtureChange");
            }
        }

        private final class Events implements StressFeed.QuietEvents {

            private final ConnectionState racingOn;
            private final AtomicBoolean raced = new AtomicBoolean();

            Events(ConnectionState racingOn) {
                this.racingOn = racingOn;
            }

            @Override
            public void onConnectionStateChange(ConnectionStateChange change) {
                late("onConnectionStateChange");
                if (source == Source.RACING_OPEN && change.state() == racingOn && raced.compareAndSet(false, true)) {
                    Thread.ofVirtual().name("stress-racing-closer").start(Cycle.this::closeAndMark);
                }
            }

            @Override
            public void onProducerStatusChange(ProducerStatus status) {
                late("onProducerStatusChange");
                if (source == Source.EVENTS_CALLBACK) {
                    // on the events thread itself: the close must not wait for the callback that makes it
                    closeAndMark();
                }
            }

            @Override
            public void onConnectionDown() {
                late("onConnectionDown");
            }

            @Override
            public void onEventRecoveryCompleted(URN eventId, long requestId) {
                late("onEventRecoveryCompleted");
            }

            @Override
            public void onFatalError(String reason, Throwable cause) {
                late("onFatalError");
            }

            @Override
            public void onHealthEvent(HealthEvent event) {
                late("onHealthEvent");
                health.onHealthEvent(event);
            }
        }
    }
}
