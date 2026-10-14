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
