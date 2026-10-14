package com.oddin.oddsfeedsdk.stress;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.OddsFeedConfigurationBuilder;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * What the scenarios that build a feed of their own share: its configuration against the fakes, the
 * listeners that only care about one callback, the fixture change that probes a session's binding,
 * and a timed call.
 */
final class StressFeed {

    private StressFeed() {}

    /** A feed's configuration against these fakes; the ledger stamps sequences, so old messages are fine. */
    static OddsFeed feed(
            GlobalEventsListener events,
            FakeFeed broker,
            FakeRestServer api,
            UnaryOperator<OddsFeedConfigurationBuilder> configure) {
        var configuration = configure
                .apply(OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                        .setMessagingSslContext(TestTls.clientContext())
                        .setAccessToken("token")
                        // the ledger stamps sequences, not times: its messages grow old as a long run goes on
                        .setStaleMessageLimit(Duration.ofHours(24)))
                .build();
        return new OddsFeed(events, configuration);
    }

    /** A fixture change of this match: only a session's queue takes it, so routing it shows the session is bound. */
    static String fixtureChange(String match, int changeType) {
        return Fixtures.replace(
                Fixtures.replace(Fixtures.read("feed/fixture_change/fixture_change.xml"), "od:match:198314", match),
                "change_type=\"1\"",
                "change_type=\"" + changeType + "\"");
    }

    /**
     * Sends again until the broker routes it, or fails after the wait: the way to know a session's
     * queue is bound before anything counts.
     */
    static void awaitRouted(BooleanSupplier send, String what, Duration wait) throws InterruptedException {
        long until = System.nanoTime() + wait.toNanos();
        while (!send.getAsBoolean()) {
            if (System.nanoTime() >= until) {
                throw new AssertionError(what + " not routed within " + wait);
            }
            Thread.sleep(100);
        }
    }

    /** How long the call took. */
    static Duration timed(Runnable call) {
        long started = System.nanoTime();
        call.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    /**
     * How long the call took, with its own deadline: the call runs in a thread of its own, so that one that
     * never returns fails here, on time, instead of holding the test until something outside ends it. What
     * the call throws is thrown here.
     */
    static Duration timedWithin(Duration limit, String what, Runnable call) {
        var failure = new AtomicReference<Throwable>();
        long started = System.nanoTime();
        var thread = new Thread(
                () -> {
                    try {
                        call.run();
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                },
                "stress-bounded-" + what);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(limit.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " was interrupted", e);
        }
        var took = Duration.ofNanos(System.nanoTime() - started);
        if (thread.isAlive()) {
            throw new AssertionError(what + " did not return within " + limit);
        }
        if (failure.get() instanceof RuntimeException e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
        return took;
    }

    /**
     * The steps of a scenario's cleanup, each run whatever the one before did. What they throw is kept
     * until the end, and then thrown, or, when the scenario had failed already, attached to that failure:
     * the first failure of a run is the one that gets reported.
     */
    static final class Cleanup {

        /** One step. */
        interface Step {
            void run() throws Exception;
        }

        private final List<Throwable> problems = new ArrayList<>();

        void step(Step step) {
            try {
                step.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                problems.add(e);
            } catch (Throwable e) {
                problems.add(e);
            }
        }

        void finish(Throwable primary) throws Exception {
            if (problems.isEmpty()) {
                return;
            }
            if (primary != null) {
                problems.forEach(primary::addSuppressed);
                return;
            }
            var first = problems.getFirst();
            problems.stream().skip(1).forEach(first::addSuppressed);
            if (first instanceof Error error) {
                throw error;
            }
            throw (Exception) first;
        }
    }

    /** A session's listener that is told of odds changes only. */
    interface OddsChanges extends OddsFeedListener {

        @Override
        default void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {}

        @Override
        default void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {}

        @Override
        default void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {}

        @Override
        default void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {}

        @Override
        default void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {}

        @Override
        default void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {}
    }

    /** A global listener that overrides what it wants of the callbacks and ignores the others. */
    interface QuietEvents extends GlobalEventsListener {

        @Override
        default void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        default void onConnectionDown() {}

        @Override
        default void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
