package com.oddin.oddsfeedsdk.internal.feed;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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
                var live = OpenFeed.build(core, plan(core, false), configuration);
                assertThat(live.toldOfTheConnection())
                        .as("told of the connection, in turn")
                        .hasSize(2)
                        .satisfies(told -> assertThat(told.getFirst()).isSameAs(live.actor()))
                        .satisfies(told -> assertThat(told.getLast()).isSameAs(core.events()));
                live.close();

                var replay = OpenFeed.build(core, plan(core, true), configuration);
                assertThat(replay.actor()).as("a replay feed's recovery").isNull();
                assertThat(replay.toldOfTheConnection()).containsExactly(core.events());
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
