package com.oddin.oddsfeedsdk.internal.dispatch;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpSettings;
import com.oddin.oddsfeedsdk.internal.amqp.AmqpTransport;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.amqp.RoutingKeys;
import com.oddin.oddsfeedsdk.internal.amqp.SessionTransport;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.message.MessageWorld;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
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
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The dispatchers on their own threads behind a real broker: what the fake feed publishes reaches the
 * session's callbacks in order, on the session's thread, and is acknowledged on the broker; the
 * SDK's own alives reach the recovery side through the alive dispatcher.
 */
class DispatchOverABrokerTest {

    private static final long WAIT_SECONDS = 20;

    private final MessageWorld world = MessageWorld.start();
    private static @Nullable FakeFeed feed;
    private final EventsDispatcher events = new EventsDispatcher(new Quiet(), null, world.producers);
    private final ClockOffsets offsets = new ClockOffsets();
    private final BlockingQueue<String> heard = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> alives = new LinkedBlockingQueue<>();
    private final AliveDispatcher aliveDispatcher = new AliveDispatcher(
            world.decoder,
            offsets,
            (producer, generatedAt, receivedAt, subscribed) -> alives.add(producer + " " + subscribed));
    private final AmqpTransport transport = new AmqpTransport(
            new AmqpSettings(
                    feed().host(),
                    feed().port(),
                    feed().virtualHost(),
                    "test-token",
                    TestTls.clientContext(),
                    "of-sdk-test",
                    10,
                    1 << 20,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)),
            FakeFeed.EXCHANGE,
            ConnectionEvents.NONE,
            aliveDispatcher);
    private final SessionDispatcher dispatcher;
    private volatile Runnable onBetStop = () -> {};

    @BeforeAll
    static void startTheBroker() {
        feed = FakeFeed.start();
    }

    @AfterAll
    static void stopTheBroker() {
        requireNonNull(feed).close();
    }

    DispatchOverABrokerTest() {
        SessionTransport channel =
                transport.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
        var pipeline = new Pipeline(
                world.decoder,
                world.messages,
                world.matches,
                world.profiles,
                world.producers,
                new FixtureChanges(),
                offsets,
                events,
                InstantSource.system());
        dispatcher = new SessionDispatcher(
                1,
                new OddsFeedSession() {},
                MessageInterest.ALL,
                new Listener(),
                null,
                channel,
                new Facts(),
                false,
                pipeline);
        events.start();
        aliveDispatcher.start();
        dispatcher.start();
        transport.open();
    }

    @AfterEach
    void close() {
        transport.close();
        dispatcher.close();
        aliveDispatcher.close();
        events.close();
        world.close();
    }

    @Test
    void whatTheFeedPublishesReachesTheSessionsCallbacksInOrderAndIsAcknowledged() throws InterruptedException {
        for (String fixture : List.of(
                "feed/odds_change/odds_change_markets_only.xml",
                "feed/bet_stop/bet_stop_all_groups.xml",
                "feed/bet_settlement/bet_settlement.xml",
                "feed/bet_cancel/bet_cancel.xml",
                "feed/fixture_change/fixture_change.xml")) {
            assertThat(feed().publishFixture(fixture)).as(fixture).isTrue();
        }
        assertThat(List.of(next(heard), next(heard), next(heard), next(heard), next(heard)))
                .containsExactly(
                        "onOddsChange oddsfeed-session-1",
                        "onBetStop oddsfeed-session-1",
                        "onBetSettlement oddsfeed-session-1",
                        "onBetCancel oddsfeed-session-1",
                        "onFixtureChange oddsfeed-session-1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (feed().unacknowledged() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(feed().unacknowledged())
                .as("left unacknowledged on the broker")
                .isZero();
        assertThat(dispatcher.handled())
                .as("handled, the alives of other tests aside")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void theSdksAlivesReachTheRecoverySideAndTheSessionsItsFacts() throws InterruptedException {
        assertThat(feed().publish(FeedMessages.alive(2, true))).isTrue();
        assertThat(next(alives)).as("from the SDK's alive channel").isEqualTo("2 true");
        assertThat(next(heard)).as("from the session's own queue").startsWith("alive 2 ");
    }

    @Test
    void aCallbackThatClosesTheSessionDoesNotWaitForItself() throws InterruptedException {
        var closedIn = new LinkedBlockingQueue<Long>();
        onBetStop = () -> {
            long start = System.nanoTime();
            dispatcher.close();
            closedIn.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        };
        assertThat(feed().publishFixture("feed/bet_stop/bet_stop_all_groups.xml"))
                .isTrue();
        assertThat(requireNonNull(closedIn.poll(WAIT_SECONDS, TimeUnit.SECONDS), "closed"))
                .as("millis, well within the wait for a callback to end")
                .isLessThan(1_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (feed().unacknowledged() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(feed().unacknowledged())
                .as("the bet stop acknowledged all the same")
                .isZero();
    }

    private static FakeFeed feed() {
        return requireNonNull(feed);
    }

    private static String next(BlockingQueue<String> queue) throws InterruptedException {
        return requireNonNull(queue.poll(WAIT_SECONDS, TimeUnit.SECONDS), "the next one");
    }

    private final class Listener implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
            heard("onOddsChange");
        }

        @Override
        public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
            heard("onBetStop");
            onBetStop.run();
        }

        @Override
        public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
            heard("onBetSettlement");
        }

        @Override
        public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
            heard("onRollbackBetSettlement");
        }

        @Override
        public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
            heard("onRollbackBetCancel");
        }

        @Override
        public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
            heard("onBetCancel");
        }

        @Override
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
            heard("onFixtureChange");
        }

        private void heard(String callback) {
            heard.add(callback + " " + Thread.currentThread().getName());
        }
    }

    private final class Facts implements SessionFacts {
        @Override
        public void processed(long producerId, long generatedAt, long takenAt, long requestId) {}

        @Override
        public void alive(long producerId, long generatedAt, long takenAt, boolean subscribed) {
            heard.add("alive " + producerId + " " + Thread.currentThread().getName());
        }

        @Override
        public void snapshotComplete(long producerId, long requestId) {}

        @Override
        public void channelLost() {}

        @Override
        public void channelReopened() {}

        @Override
        public void closed() {}
    }

    private static final class Quiet implements GlobalEventsListener {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
