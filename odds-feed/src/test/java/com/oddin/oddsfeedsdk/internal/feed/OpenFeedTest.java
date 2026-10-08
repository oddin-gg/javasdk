package com.oddin.oddsfeedsdk.internal.feed;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.session.SessionRegistry;
import com.oddin.oddsfeedsdk.internal.session.Sessions;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What open() adds, closed before it starts: it then starts nothing. */
class OpenFeedTest {

    @Test
    void closedBeforeItStartsItStartsNoThreadAndConnectsNowhere() {
        try (var api = FakeRestServer.start()) {
            var configuration = configuration(api);
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var sessions = new SessionRegistry(null);
                sessions.builder()
                        .setListener(new Silent())
                        .setMessageInterest(MessageInterest.ALL)
                        .build();
                var plan = Sessions.plan(sessions.open(), core.producers().getAvailableProducers(), null);
                var before = Thread.getAllStackTraces().keySet();
                var open = OpenFeed.build(core, plan, configuration);

                assertThat(open.stop()).as("the first close").isTrue();
                assertThat(open.stop()).as("a second").isFalse();
                assertThatThrownBy(open::start)
                        .isInstanceOf(InitException.class)
                        .hasMessage("Failed to open the feed: the feed was closed as it opened");
                assertThat(open.awaitStop(System.nanoTime())).isTrue();
                assertThat(Thread.getAllStackTraces().keySet().stream()
                                .filter(thread -> !before.contains(thread))
                                .map(Thread::getName)
                                .filter(name -> name.startsWith("oddsfeed"))
                                .collect(Collectors.toSet()))
                        .as("threads started")
                        .isEqualTo(Set.of());
            } finally {
                core.close();
            }
        }
    }

    @Test
    void theRecoveryActorHearsOfTheConnectionBeforeTheClientAndAReplayFeedRunsNone() {
        try (var api = FakeRestServer.start()) {
            var configuration = configuration(api);
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var health = new HealthMonitor(core.events(), id -> null);
                var live = OpenFeed.build(core, plan(core, false), configuration, health);
                assertThat(live.toldOfTheConnection())
                        .as("told of the connection, in turn: the recovery, the client, the health")
                        .hasSize(3)
                        .satisfies(told -> assertThat(told.getFirst()).isSameAs(live.actor()))
                        .satisfies(told -> assertThat(told.get(1)).isSameAs(core.events()))
                        .satisfies(told -> assertThat(told.getLast()).isSameAs(health));
                assertThat(live.toldOfTheRecovery())
                        .as("told of the recovery's events, the client first")
                        .containsExactly(core.events(), health);
                live.close();

                var replay = OpenFeed.build(core, plan(core, true), configuration, health);
                assertThat(replay.actor()).as("a replay feed's recovery").isNull();
                assertThat(replay.toldOfTheConnection()).containsExactly(core.events(), health);
                assertThat(replay.toldOfTheRecovery()).isEmpty();
                replay.close();
            } finally {
                core.close();
            }
        }
    }

    @Test
    void theRecoveryActorRunsBeforeTheTransportOpens() {
        try (var api = FakeRestServer.start()) {
            var configuration = configuration(api);
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var open = OpenFeed.build(core, plan(core, false), configuration);
                var actor = requireNonNull(open.actor());
                var turned = new AtomicBoolean();
                open.beforeTransportOpens = () -> {
                    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (actor.turns() == 0 && System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    turned.set(actor.turns() > 0);
                };

                // no broker listens: the transport cannot open, and the start closes what it started
                assertThatThrownBy(open::start).isInstanceOf(InitException.class);
                assertThat(turned)
                        .as("the actor running as the transport opens")
                        .isTrue();
            } finally {
                core.close();
            }
        }
    }

    @Test
    void anEventRecoverysCallerWaitsForTheHttpTimeoutAndASecondAtMost() throws Exception {
        try (var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                    .setAccessToken("token")
                    .setHttpClientTimeout(Duration.ofSeconds(1))
                    .build();
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            var open = OpenFeed.build(core, plan(core, false), configuration);
            try {
                // the actor never started: nothing answers
                long asked = System.nanoTime();
                var answer =
                        CompletableFuture.supplyAsync(() -> open.recoverEvent(2, URN.parse("od:match:198314"), false));
                assertThat(answer.get(10, TimeUnit.SECONDS)).as("not answered").isNull();
                assertThat(Duration.ofNanos(System.nanoTime() - asked))
                        .as("the caller's wait")
                        .isBetween(Duration.ofSeconds(2), Duration.ofMillis(3_500));

                // the request the caller gave up on is not made once the actor runs
                var actor = requireNonNull(open.actor());
                actor.start();
                long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (actor.counters().eventCallerGone() == 0 && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
                assertThat(actor.counters().eventCallerGone()).isEqualTo(1);
            } finally {
                open.close();
                core.close();
            }
        }
    }

    @Test
    void aCallerWhoseWaitEndsAsTheActorAnswersHasTheIdTheRequestWentOutWith() {
        var match = URN.parse("od:match:198314");
        assertThat(OpenFeed.answer(answeredAsTheWaitEnds(reply -> reply.complete(7L)), Duration.ZERO, 2, match))
                .as("answered in the instant the wait ended")
                .isEqualTo(7L);
        assertThat(OpenFeed.answer(
                        answeredAsTheWaitEnds(reply -> reply.completeExceptionally(new IllegalStateException("no"))),
                        Duration.ZERO,
                        2,
                        match))
                .as("refused in that instant")
                .isNull();

        var unanswered = new CompletableFuture<@Nullable Long>();
        assertThat(OpenFeed.answer(unanswered, Duration.ofMillis(10), 2, match)).isNull();
        assertThat(unanswered.complete(9L)).as("answered null for the actor").isFalse();
        assertThat(unanswered.getNow(9L)).isNull();

        Thread.currentThread().interrupt();
        try {
            var abandoned = new CompletableFuture<@Nullable Long>();
            assertThat(OpenFeed.answer(abandoned, Duration.ofSeconds(10), 2, match))
                    .isNull();
            assertThat(abandoned.complete(9L))
                    .as("answered null for the actor, as the caller was interrupted")
                    .isFalse();
            assertThat(abandoned.getNow(9L)).isNull();
            assertThat(OpenFeed.answer(answeredAsTheWaitIsInterrupted(), Duration.ofSeconds(10), 2, match))
                    .as("answered as the caller was interrupted")
                    .isEqualTo(8L);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt kept")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** A reply the actor answers in the instant the caller's wait times out, before it answers for it. */
    private static CompletableFuture<@Nullable Long> answeredAsTheWaitEnds(
            Consumer<CompletableFuture<@Nullable Long>> answer) {
        return new CompletableFuture<>() {
            @Override
            public @Nullable Long get(long timeout, TimeUnit unit) throws TimeoutException {
                answer.accept(this);
                throw new TimeoutException();
            }
        };
    }

    /** A reply the actor answers in the instant the caller's wait is interrupted. */
    private static CompletableFuture<@Nullable Long> answeredAsTheWaitIsInterrupted() {
        return new CompletableFuture<>() {
            @Override
            public @Nullable Long get(long timeout, TimeUnit unit) throws InterruptedException {
                complete(8L);
                throw new InterruptedException();
            }
        };
    }

    @Test
    void aCloseThatComesAsTheTransportOpensFailsTheStart() throws Exception {
        try (var broker = FakeFeed.start();
                var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                    .setMessagingSslContext(TestTls.clientContext())
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var open = OpenFeed.build(core, plan(core, false), configuration);
                // the feed's close() as the transport finishes opening: its first half, then its second
                var closer = new CompletableFuture<Boolean>();
                open.afterTransportOpens = () -> {
                    assertThat(broker.openConnections()).as("connected").isNotEmpty();
                    open.stop();
                    closer.completeAsync(() -> open.awaitStop(
                            System.nanoTime() + Duration.ofSeconds(5).toNanos()));
                };

                assertThatThrownBy(open::start)
                        .isInstanceOf(InitException.class)
                        .hasMessage("Failed to open the feed: the feed was closed as it opened");
                assertThat(closer.get(10, TimeUnit.SECONDS))
                        .as("the close's wait")
                        .isTrue();
                long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!broker.openConnections().isEmpty() && System.nanoTime() < until) {
                    Thread.sleep(50);
                }
                assertThat(broker.openConnections()).as("connections left").isEmpty();
            } finally {
                core.close();
            }
        }
    }

    @Test
    void anOpenFeedKeepsOneNonDaemonThreadFromItsStartToItsClose() throws Exception {
        try (var broker = FakeFeed.start();
                var api = FakeRestServer.start()) {
            var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment(broker.host(), api.apiHost(), broker.port())
                    .setMessagingSslContext(TestTls.clientContext())
                    .setAccessToken("token")
                    .build();
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var before = Thread.getAllStackTraces().keySet();
                var open = OpenFeed.build(core, plan(core, false), configuration);
                assertThat(nonDaemonFeedThreads(before)).as("built").isEmpty();
                open.start();
                Set<Thread> started = nonDaemonFeedThreads(before);
                assertThat(started)
                        .as("open: the one thread that keeps the JVM up during an outage")
                        .extracting(Thread::getName)
                        .containsExactly("oddsfeed-keep-alive");

                open.close();
                assertThat(started).as("closed").noneMatch(Thread::isAlive);
                assertThat(nonDaemonFeedThreads(before)).isEmpty();
            } finally {
                core.close();
            }
        }
    }

    @Test
    void aStartThatFailsEndsTheNonDaemonThreadItStarted() {
        try (var api = FakeRestServer.start()) {
            var configuration = configuration(api);
            var core = FeedCore.start(configuration, new EventsDispatcher(new Quiet(), null, id -> null), client -> {});
            try {
                var before = Thread.getAllStackTraces().keySet();
                var open = OpenFeed.build(core, plan(core, false), configuration);
                var started = new CompletableFuture<Set<Thread>>();
                open.beforeTransportOpens = () -> started.complete(nonDaemonFeedThreads(before));

                // no broker listens: the transport cannot open, and the start closes what it started
                assertThatThrownBy(open::start).isInstanceOf(InitException.class);
                assertThat(started.getNow(Set.of()))
                        .as("as the transport opened")
                        .extracting(Thread::getName)
                        .containsExactly("oddsfeed-keep-alive");
                assertThat(started.getNow(Set.of())).as("once the start failed").noneMatch(Thread::isAlive);
            } finally {
                core.close();
            }
        }
    }

    /** The feed's live non-daemon threads not among {@code before}. */
    private static Set<Thread> nonDaemonFeedThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.isAlive() && !thread.isDaemon())
                .filter(thread -> thread.getName().startsWith("oddsfeed"))
                .collect(Collectors.toSet());
    }

    private static Sessions.Plan plan(FeedCore core, boolean replay) {
        var sessions = new SessionRegistry(null);
        var builder = sessions.builder().setListener(new Silent());
        if (replay) {
            builder.buildReplay();
        } else {
            builder.setMessageInterest(MessageInterest.ALL).build();
        }
        return Sessions.plan(sessions.open(), core.producers().getAvailableProducers(), null);
    }

    private static OddsFeedConfiguration configuration(FakeRestServer api) {
        // a broker nothing listens on: a start that connected would fail loudly
        return OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("127.0.0.1", api.apiHost(), 1)
                .setAccessToken("token")
                .build();
    }

    private static final class Quiet implements GlobalEventsListener {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }

    private static final class Silent implements OddsFeedListener {
        @Override
        public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {}

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
        public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {}
    }
}
