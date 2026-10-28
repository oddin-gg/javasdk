package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.StatusCause;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ApiCallEvent;
import com.oddin.oddsfeedsdk.subscribe.CallbackFailure;
import com.oddin.oddsfeedsdk.subscribe.ConnectionStateChange;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.HealthComponent;
import com.oddin.oddsfeedsdk.subscribe.HealthEvent;
import com.oddin.oddsfeedsdk.subscribe.HealthState;
import com.oddin.oddsfeedsdk.subscribe.ProducerCauseChange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The feed before it opens: its start on the first call that needs it, its events, and its close. */
class OddsFeedTest {

    private static final String WHOAMI = "/v1/users/whoami";
    private static final String PRODUCERS = "/v1/descriptions/producers";
    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String SUMMARY = "/v1/sports/en/sport_events/od:match:198314/summary";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final String FORBIDDEN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <response response_code="FORBIDDEN"><action>whoami</action><message>no access</message></response>
      """;

    private static final GlobalEventsListener NO_EVENTS = new GlobalEventsListener() {
        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    };

    @Test
    void theManagersWorkBeforeTheFeedOpens() {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api);
            try {
                assertThat(feed.getBookMakerDetail().getBookmakerId()).isEqualTo(53);
                assertThat(feed.getProducerManager().getAvailableProducers()).containsKeys(1L, 2L);
                assertThat(requireNonNull(feed.getSportsInfoManager().getMatch(MATCH))
                                .getName(Locale.ENGLISH))
                        .isEqualTo("Team Alpha vs Team Beta");
                assertThat(feed.getMarketDescriptionManager().getMarketDescriptions())
                        .isNotEmpty();
                assertThat(feed.getReplayManager().getReplayList())
                        .as("each id of the replay list a match, named in the default locale")
                        .singleElement()
                        .isInstanceOfSatisfying(Match.class, match -> {
                            assertThat(match.getId()).isEqualTo(MATCH);
                            assertThat(match.getName(Locale.ENGLISH)).isEqualTo("Team Alpha vs Team Beta");
                        });
                assertThat(feed.getRecoveryManager().initiateEventOddsMessagesRecovery(1, MATCH))
                        .as("not accepted before the feed opens")
                        .isNull();
                assertThat(feed.getProducerManager()).isSameAs(feed.getProducerManager());
                api.awaitQuiet();
            } finally {
                feed.close();
            }
            assertThat(api.requests("GET", WHOAMI)).as("one start").hasSize(1);
        }
    }

    @Test
    void theHealthStartsNothingAndHasTheEventsTheCatalogsAndTheWatchOnceTheFeedHasStarted() {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api);
            try {
                var before = feed.getHealth();
                assertThat(before.components()).as("no part yet").isEmpty();
                assertThat(before.state()).isEqualTo(HealthState.HEALTHY);
                assertThat(before.catalogs()).isEmpty();
                assertThat(before.sessions()).isEmpty();
                assertThat(api.requests("GET", WHOAMI))
                        .as("the health starts nothing")
                        .isEmpty();

                feed.getMarketDescriptionManager().getMarketDescriptions();
                var started = feed.getHealth();
                assertThat(started.components())
                        .containsExactly(
                                Map.entry(HealthComponent.EVENTS, HealthState.HEALTHY),
                                Map.entry(HealthComponent.TIMERS, HealthState.HEALTHY),
                                Map.entry(HealthComponent.THREADS, HealthState.HEALTHY),
                                Map.entry(HealthComponent.CATALOGS, HealthState.HEALTHY),
                                Map.entry(HealthComponent.API, HealthState.HEALTHY));
                assertThat(started.catalogs()).hasSize(4);
                assertThat(started.sessions()).as("none before the feed opens").isEmpty();
                assertThat(started.transport().connected()).isFalse();
                assertThat(started.counters()).containsEntry("catalog.market_descriptions.state", 0L);
                api.awaitQuiet();
            } finally {
                feed.close();
            }
            assertThat(feed.getHealth().components())
                    .as("read once closed too")
                    .containsOnlyKeys(
                            HealthComponent.EVENTS,
                            HealthComponent.TIMERS,
                            HealthComponent.THREADS,
                            HealthComponent.CATALOGS,
                            HealthComponent.API);
        }
    }

    @Test
    void anApiCallOfAManagerIsReportedAsItIsMadeLongBeforeTheFeedOpens() throws Exception {
        try (var api = FakeRestServer.start()) {
            var calls = new LinkedBlockingQueue<ApiCallEvent>();
            var feed = new OddsFeed(
                    new GlobalEventsListener() {
                        @Override
                        public void onProducerStatusChange(ProducerStatus producerStatus) {}

                        @Override
                        public void onConnectionDown() {}

                        @Override
                        public void onEventRecoveryCompleted(URN eventId, long requestId) {}

                        @Override
                        public void onApiCall(ApiCallEvent call) {
                            calls.add(call);
                        }
                    },
                    configurationAgainst(api));
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            try {
                requireNonNull(feed.getSportsInfoManager().getMatch(MATCH)).getName(Locale.ENGLISH);

                var paths = new ArrayList<String>();
                long until = System.nanoTime() + WAIT.toNanos();
                while (paths.stream().noneMatch(path -> path.contains("198314")) && System.nanoTime() < until) {
                    var call = calls.poll(100, TimeUnit.MILLISECONDS);
                    if (call != null) {
                        paths.add(call.uri().getPath());
                    }
                }
                assertThat(paths)
                        .as("the calls reported, the start's own first, and open() never called")
                        .first()
                        .isEqualTo(WHOAMI);
                assertThat(paths).anySatisfy(path -> assertThat(path).contains("198314"));
                api.awaitQuiet();
            } finally {
                feed.close();
            }
            awaitNoThreadsBut(before);
        }
    }

    @Test
    void aStartThatFailsThrowsAndTheNextCallStartsAgain() {
        try (var api = FakeRestServer.start()) {
            api.respond(WHOAMI, 403, FORBIDDEN);
            var feed = feedAgainst(api);
            try {
                assertThatThrownBy(feed::getProducerManager)
                        .isInstanceOf(InitException.class)
                        .hasMessageStartingWith("Failed to init odds feed")
                        .cause()
                        .isInstanceOf(ApiException.class);
                assertThat(api.requests("GET", PRODUCERS)).isEmpty();

                api.respond(WHOAMI, 200, Fixtures.read("rest/whoami/bookmaker_details.xml"));

                assertThat(feed.getBookMakerDetail().getBookmakerId()).isEqualTo(53);
                assertThat(api.requests("GET", WHOAMI)).hasSize(2);
            } finally {
                feed.close();
            }
        }
    }

    @Test
    void aRefusedTokenIsTheApiPartsStallInGetHealthAndToldUntilACallSucceeds() throws Exception {
        try (var api = FakeRestServer.start()) {
            var changes = new LinkedBlockingQueue<HealthEvent>();
            var fatal = new LinkedBlockingQueue<String>();
            var feed = new OddsFeed(
                    new GlobalEventsListener() {
                        @Override
                        public void onProducerStatusChange(ProducerStatus producerStatus) {}

                        @Override
                        public void onConnectionDown() {}

                        @Override
                        public void onEventRecoveryCompleted(URN eventId, long requestId) {}

                        @Override
                        public void onHealthEvent(HealthEvent event) {
                            changes.add(event);
                        }

                        @Override
                        public void onFatalError(String reason, @Nullable Throwable cause) {
                            fatal.add(reason);
                        }
                    },
                    configurationAgainst(api));
            try {
                api.respond(WHOAMI, 403, FORBIDDEN);
                assertThatThrownBy(feed::getBookMakerDetail).isInstanceOf(InitException.class);
                assertThat(requireNonNull(fatal.poll(WAIT.toSeconds(), TimeUnit.SECONDS)))
                        .as("the fatal error is told as before")
                        .startsWith("The API refused the access token: GET ")
                        .contains(" answered 403");
                var refused = requireNonNull(changes.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
                assertThat(refused.component()).isEqualTo(HealthComponent.API);
                assertThat(refused.state()).isEqualTo(HealthState.STALLED);
                assertThat(refused.reason())
                        .startsWith("the API refused the access token: GET ")
                        .contains(" answered 403 at ");
                var polled = feed.getHealth();
                assertThat(polled.components()).containsEntry(HealthComponent.API, HealthState.STALLED);
                assertThat(polled.state()).isEqualTo(HealthState.STALLED);

                api.respond(WHOAMI, 200, Fixtures.read("rest/whoami/bookmaker_details.xml"));
                feed.getBookMakerDetail();
                var accepted = requireNonNull(changes.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
                assertThat(accepted.component()).isEqualTo(HealthComponent.API);
                assertThat(accepted.state()).isEqualTo(HealthState.HEALTHY);
                assertThat(feed.getHealth().components()).containsEntry(HealthComponent.API, HealthState.HEALTHY);
            } finally {
                feed.close();
            }
        }
    }

    @Test
    void aStartThatFailsDoesNotWaitForAnEventsCallbackThatWaitsForTheStart() throws Exception {
        try (var api = FakeRestServer.start()) {
            // the start fails on the producer list, a second after its call of whoami is reported
            api.respond(PRODUCERS, Reply.of(403, FORBIDDEN).after(Duration.ofSeconds(1)));
            var holder = new CompletableFuture<OddsFeed>();
            var asked = new AtomicBoolean();
            var answered = new CountDownLatch(1);
            var feed = new OddsFeed(
                    new GlobalEventsListener() {
                        @Override
                        public void onProducerStatusChange(ProducerStatus producerStatus) {}

                        @Override
                        public void onConnectionDown() {}

                        @Override
                        public void onEventRecoveryCompleted(URN eventId, long requestId) {}

                        @Override
                        public void onApiCall(ApiCallEvent call) {
                            if (!asked.compareAndSet(false, true)) {
                                return;
                            }
                            // waits for the start under way, whose lock it needs
                            try {
                                holder.join().getProducerManager();
                            } catch (InitException failedToo) {
                                // the start it waited for failed, and so did the one it made
                            } finally {
                                answered.countDown();
                            }
                        }
                    },
                    configurationAgainst(api));
            holder.complete(feed);
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            try {
                long starting = System.nanoTime();
                assertThatThrownBy(feed::getBookMakerDetail).isInstanceOf(InitException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - starting))
                        .as("a failed start, with a callback waiting for it")
                        .isLessThan(Duration.ofSeconds(3));
                assertThat(answered.await(WAIT.toSeconds(), TimeUnit.SECONDS))
                        .as("the callback went on once the start had failed")
                        .isTrue();
            } finally {
                feed.close();
            }
            awaitNoThreadsBut(before);
        }
    }

    @Test
    void aStartThatFailsAndTheNextTellTheClientOnOneThread() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(WHOAMI, 403, FORBIDDEN);
            var threads = new LinkedBlockingQueue<Thread>();
            var feed = new OddsFeed(apiCalls(call -> threads.add(Thread.currentThread())), configurationAgainst(api));
            try {
                assertThatThrownBy(feed::getBookMakerDetail).isInstanceOf(InitException.class);
                var failed = threads.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
                assertThat(failed).as("the failed start's call, told").isNotNull();

                api.respond(WHOAMI, 200, Fixtures.read("rest/whoami/bookmaker_details.xml"));
                feed.getBookMakerDetail();
                var next = threads.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
                assertThat(next)
                        .as("the next start's call, told on the thread that told the failed one's")
                        .isSameAs(failed);
                api.awaitQuiet();
            } finally {
                feed.close();
            }
        }
    }

    @Test
    void closingWaitsForACallbackOfAStartThatFailed() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(WHOAMI, 403, FORBIDDEN);
            var entered = new CountDownLatch(1);
            var returned = new AtomicBoolean();
            var feed = new OddsFeed(
                    apiCalls(call -> {
                        if (entered.getCount() == 0) {
                            return;
                        }
                        entered.countDown();
                        try {
                            Thread.sleep(Duration.ofSeconds(1));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        returned.set(true);
                    }),
                    configurationAgainst(api));
            assertThatThrownBy(feed::getBookMakerDetail).isInstanceOf(InitException.class);
            assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            feed.close();
            assertThat(returned).as("the callback returned before close() did").isTrue();
        }
    }

    @Test
    void callersThatComeAtOnceStartTheFeedOnce() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(
                    WHOAMI,
                    Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml"))
                            .after(Duration.ofMillis(300)));
            var feed = feedAgainst(api);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Function<OddsFeed, Object>> getters = List.of(
                        OddsFeed::getBookMakerDetail,
                        OddsFeed::getProducerManager,
                        OddsFeed::getSportsInfoManager,
                        OddsFeed::getMarketDescriptionManager,
                        OddsFeed::getReplayManager,
                        OddsFeed::getRecoveryManager);
                var go = new CountDownLatch(1);
                var calls = new ArrayList<CompletableFuture<Object>>();
                for (var getter : getters) {
                    calls.add(CompletableFuture.supplyAsync(
                            () -> {
                                await(go);
                                return getter.apply(feed);
                            },
                            threads));
                }
                go.countDown();
                for (var call : calls) {
                    assertThat(call.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isNotNull();
                }
                assertThat(api.requests("GET", WHOAMI)).hasSize(1);
                assertThat(api.requests("GET", PRODUCERS)).hasSize(1);
            } finally {
                feed.close();
            }
        }
    }

    @Test
    void aFeedClosedBeforeItStartedDoesNotStart() {
        try (var api = FakeRestServer.start()) {
            var feed = feedAgainst(api);
            feed.close();

            assertThatThrownBy(feed::getSportsInfoManager)
                    .isInstanceOf(InitException.class)
                    .hasMessage("Failed to init odds feed: the feed was closed");
            assertThat(api.requests()).isEmpty();
            feed.close();
        }
    }

    @Test
    void closingEndsAStartUnderWay() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(
                    WHOAMI,
                    Reply.of(200, Fixtures.read("rest/whoami/bookmaker_details.xml"))
                            .after(Duration.ofSeconds(3)));
            var feed = feedAgainst(api);
            var start = CompletableFuture.supplyAsync(feed::getBookMakerDetail);
            api.awaitRequest("GET", WHOAMI);

            long closing = System.nanoTime();
            feed.close();

            assertThatThrownBy(() -> start.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(InitException.class)
                    .hasMessageContaining("the feed was closed");
            assertThat(Duration.ofNanos(System.nanoTime() - closing)).isLessThan(Duration.ofSeconds(2));
            assertThatThrownBy(feed::getBookMakerDetail).isInstanceOf(InitException.class);
        }
    }

    @Test
    void anApiCallAnsweredOnceTheCloseHasBegunReachesNoListener() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(
                    SUMMARY,
                    Reply.of(200, Fixtures.read("rest/match_summary/match_summary.xml"))
                            .stallingMidBody(Duration.ofSeconds(30)));
            var heard = new Heard(1);
            var before = Thread.getAllStackTraces().keySet();
            var feed = new OddsFeed(heard, configurationAgainst(api));
            try {
                feed.getBookMakerDetail();
                var events = eventsThread(before);
                var call = callOnAThread(feed);
                api.awaitRequest("GET", SUMMARY);

                // the feed is marked closed, and its REST client is not closed yet: the call is answered
                // now, and the closer waits until the listener has heard of it, or cannot
                feed.afterMarkedClosed = () -> {
                    heard.closeBegun = true;
                    api.releaseStalls();
                    awaitDone(call);
                    awaitHeardOrEnded(heard, events);
                };
                feed.close();

                assertThat(call).as("the call was answered").isCompleted();
                assertThat(heard.afterClose).as("events after close() began").isEmpty();
            } finally {
                api.releaseStalls();
                feed.close();
            }
        }
    }

    @Test
    void anApiCallTheCloseCutsOffReachesNoListener() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(
                    SUMMARY,
                    Reply.of(200, Fixtures.read("rest/match_summary/match_summary.xml"))
                            .stallingMidBody(Duration.ofSeconds(30)));
            var heard = new Heard(1);
            var before = Thread.getAllStackTraces().keySet();
            var feed = new OddsFeed(heard, configurationAgainst(api));
            try {
                feed.getBookMakerDetail();
                var events = eventsThread(before);
                var call = callOnAThread(feed);
                api.awaitRequest("GET", SUMMARY);

                // the REST client is closed, which fails the call in flight; the closer waits until the
                // call has been reported and the listener has heard of it, or cannot
                feed.afterMarkedClosed = () -> heard.closeBegun = true;
                feed.afterClientClosed = () -> {
                    awaitDone(call);
                    awaitHeardOrEnded(heard, events);
                };
                feed.close();

                assertThat(call).as("the call was cut off").isCompletedExceptionally();
                assertThat(heard.afterClose).as("events after close() began").isEmpty();
            } finally {
                api.releaseStalls();
                feed.close();
            }
        }
    }

    @Test
    void noEventOfAnySourceReachesTheListenerOnceTheCloseHasBegun() throws Exception {
        try (var api = FakeRestServer.start()) {
            var heard = new Heard(7);
            var before = Thread.getAllStackTraces().keySet();
            var feed = new OddsFeed(heard, configurationAgainst(api));
            try {
                feed.getBookMakerDetail();
                var events = eventsThread(before);
                var dispatcher = feed.events();

                // every source of the feed's events reports once the feed is marked closed
                feed.afterMarkedClosed = () -> {
                    heard.closeBegun = true;
                    dispatcher.up();
                    var change = new ProducerStatusChange(1, true, false, StatusCause.STARTING, 1L);
                    dispatcher.producerStatus(change);
                    dispatcher.producerCause(change);
                    dispatcher.eventRecoveryCompleted(1, MATCH, 1);
                    dispatcher.fatal("late", null);
                    dispatcher.health(new HealthEvent(
                            HealthComponent.CATALOGS,
                            null,
                            HealthState.HEALTHY,
                            HealthState.DEGRADED,
                            "late",
                            Instant.now()));
                    dispatcher.callbackFailed("late", true, new IllegalStateException("late"), null);
                    awaitHeardOrEnded(heard, events);
                };
                feed.close();

                assertThat(heard.afterClose).as("events after close() began").isEmpty();
            } finally {
                feed.close();
            }
        }
    }

    @Test
    void aListenerOfApiCallsCanCloseTheFeedAndHearsNothingMore() throws Exception {
        try (var api = FakeRestServer.start()) {
            var holder = new CompletableFuture<OddsFeed>();
            var closed = new CompletableFuture<Void>();
            var heard = new Heard(0) {
                @Override
                public void onApiCall(ApiCallEvent call) {
                    super.onApiCall(call);
                    if (call.uri().getPath().contains("198314")) {
                        holder.join().close();
                        closeBegun = true;
                        closed.complete(null);
                    }
                }
            };
            var before = Thread.getAllStackTraces().keySet();
            var feed = new OddsFeed(heard, configurationAgainst(api));
            holder.complete(feed);
            try {
                feed.getBookMakerDetail();
                var events = eventsThread(before);
                requireNonNull(feed.getSportsInfoManager().getMatch(MATCH)).getName(Locale.ENGLISH);

                closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                events.join(WAIT);
                assertThat(events.isAlive())
                        .as("the events thread ended after the callback")
                        .isFalse();
                assertThat(heard.afterClose).isEmpty();
            } finally {
                feed.close();
            }
        }
    }

    /** What the listener of a feed that is closed heard, once the test says the close has begun. */
    private static class Heard implements GlobalEventsListener {
        /** Callbacks of the events the test makes or awaits that came once {@link #closeBegun}. */
        final List<String> afterClose = new CopyOnWriteArrayList<>();
        /** Counted down by each of those callbacks, the ones heard in time too; the closer waits for it. */
        final CountDownLatch awaited;

        volatile boolean closeBegun;

        Heard(int awaited) {
            this.awaited = new CountDownLatch(awaited);
        }

        private void heard(String callback) {
            if (closeBegun) {
                afterClose.add(callback);
            }
            awaited.countDown();
        }

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {
            heard("onProducerStatusChange");
        }

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {
            heard("onEventRecoveryCompleted");
        }

        @Override
        public void onConnectionStateChange(ConnectionStateChange change) {
            heard("onConnectionStateChange");
        }

        @Override
        public void onFatalError(String reason, @Nullable Throwable cause) {
            if (reason.equals("late")) {
                heard("onFatalError");
            }
        }

        @Override
        public void onApiCall(ApiCallEvent call) {
            // the calls of the start are not the test's
            if (call.uri().getPath().contains("198314")) {
                heard("onApiCall");
            }
        }

        @Override
        public void onCallbackFailure(CallbackFailure failure) {
            if (failure.callback().equals("late")) {
                heard("onCallbackFailure");
            }
        }

        @Override
        public void onProducerCauseChange(ProducerCauseChange change) {
            heard("onProducerCauseChange");
        }

        @Override
        public void onHealthEvent(HealthEvent event) {
            if (event.reason().equals("late")) {
                heard("onHealthEvent");
            }
        }
    }

    /** The match's name read on a thread of its own: the API call it makes is the one in flight. */
    private static CompletableFuture<String> callOnAThread(OddsFeed feed) {
        var call = new CompletableFuture<String>();
        Thread.startVirtualThread(() -> {
            try {
                call.complete(requireNonNull(feed.getSportsInfoManager().getMatch(MATCH))
                        .getName(Locale.ENGLISH));
            } catch (Throwable e) {
                call.completeExceptionally(e);
            }
        });
        return call;
    }

    private static void awaitDone(CompletableFuture<?> call) {
        try {
            call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // cut off: done
        } catch (TimeoutException e) {
            throw new AssertionError("the call did not end", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits until the listener has heard what the test awaits, or the events thread has ended and
     * never will: a feed that delivers after its close began ends in the first, one that does not in
     * the second. Spins, so the events thread is not woken by a wait.
     */
    private static void awaitHeardOrEnded(Heard heard, Thread events) {
        long until = System.nanoTime() + WAIT.toNanos();
        while (heard.awaited.getCount() > 0
                && events.getState() != Thread.State.TERMINATED
                && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    /** The events thread of the feed made since {@code before}. */
    private static Thread eventsThread(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.getName().equals("oddsfeed-events"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void closingLeavesNoThreadOfTheFeedBehind() throws Exception {
        try (var api = FakeRestServer.start()) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            var started = feedAgainst(api);
            requireNonNull(started.getSportsInfoManager().getMatch(MATCH)).getName(Locale.ENGLISH);
            api.awaitQuiet();
            assertThat(feedThreads(before))
                    .as(
                            "the REST client's, the events dispatcher's and the watchdog's, while the feed runs, never opened")
                    .contains("oddsfeed-events", "oddsfeed-timer");
            started.close();

            api.respond(WHOAMI, 403, FORBIDDEN);
            var failed = feedAgainst(api);
            assertThatThrownBy(failed::getBookMakerDetail).isInstanceOf(InitException.class);
            // a failed start released what it built itself; closing finds nothing to release
            failed.close();

            awaitNoThreadsBut(before);
        }
    }

    @Test
    void aFailedStartKeepsItsTwoIdleDaemonThreadsUntilTheCloseEndsThem() throws Exception {
        try (var api = FakeRestServer.start()) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            api.respond(WHOAMI, 403, FORBIDDEN);
            var failed = feedAgainst(api);
            assertThatThrownBy(failed::getBookMakerDetail).isInstanceOf(InitException.class);

            // the events dispatcher and the watchdog stay, for the next start and its callbacks
            var kept = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !before.contains(thread) && thread.isAlive())
                    .filter(thread -> thread.getName().startsWith("oddsfeed"))
                    .toList();
            assertThat(kept).extracting(Thread::getName).containsExactlyInAnyOrder("oddsfeed-events", "oddsfeed-timer");
            assertThat(kept).allMatch(Thread::isDaemon);

            failed.close();
            awaitNoThreadsBut(before);
        }
    }

    /** A listener that hears only the API calls, each with {@code call}. */
    private static GlobalEventsListener apiCalls(Consumer<ApiCallEvent> call) {
        return new GlobalEventsListener() {
            @Override
            public void onProducerStatusChange(ProducerStatus producerStatus) {}

            @Override
            public void onConnectionDown() {}

            @Override
            public void onEventRecoveryCompleted(URN eventId, long requestId) {}

            @Override
            public void onApiCall(ApiCallEvent apiCall) {
                call.accept(apiCall);
            }
        };
    }

    private static OddsFeed feedAgainst(FakeRestServer api) {
        return new OddsFeed(NO_EVENTS, configurationAgainst(api));
    }

    private static OddsFeedConfiguration configurationAgainst(FakeRestServer api) {
        return OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .build();
    }

    /** Waits for every thread of the feed's that {@code before} did not have to end. */
    private static void awaitNoThreadsBut(Set<Thread> before) throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        while (!feedThreads(before).isEmpty() && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(feedThreads(before)).as("threads the feed left").isEmpty();
    }

    /**
     * The live platform threads of the feed and its REST client that {@code before} did not have;
     * virtual threads are not listed, so the side-loads and fetches are not among them.
     */
    private static Set<String> feedThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.isAlive())
                .map(Thread::getName)
                .filter(name -> name.startsWith("HttpClient") || name.startsWith("oddsfeed"))
                .collect(Collectors.toSet());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
