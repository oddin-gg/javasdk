package com.oddin.oddsfeedsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The feed before it opens: its start on the first call that needs it, and its close. */
class OddsFeedTest {

    private static final String WHOAMI = "/v1/users/whoami";
    private static final String PRODUCERS = "/v1/descriptions/producers";
    private static final URN MATCH = URN.parse("od:match:198314");
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
                assertThat(feed.getReplayManager().getReplayList()).isNotNull();
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
    void closingLeavesNoThreadOfTheFeedBehind() throws Exception {
        try (var api = FakeRestServer.start()) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            var started = feedAgainst(api);
            requireNonNull(started.getSportsInfoManager().getMatch(MATCH)).getName(Locale.ENGLISH);
            api.awaitQuiet();
            assertThat(feedThreads(before))
                    .as("the REST client's, while the feed runs")
                    .isNotEmpty();
            started.close();

            api.respond(WHOAMI, 403, FORBIDDEN);
            var failed = feedAgainst(api);
            assertThatThrownBy(failed::getBookMakerDetail).isInstanceOf(InitException.class);
            // a failed start released what it built itself; closing finds nothing to release
            failed.close();

            awaitNoThreadsBut(before);
        }
    }

    private static OddsFeed feedAgainst(FakeRestServer api) {
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .build();
        return new OddsFeed(NO_EVENTS, configuration);
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
