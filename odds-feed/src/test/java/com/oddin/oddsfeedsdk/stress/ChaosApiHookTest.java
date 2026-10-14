package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.fakes.TestTls;
import com.oddin.oddsfeedsdk.stress.ChaosApi.Fault;
import com.oddin.oddsfeedsdk.stress.ChaosApi.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The policy installed on a real fake: what a client sees of the exemptions, the latency and the faults. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ChaosApiHookTest {

    private static final Duration STALL = Duration.ofSeconds(30);
    private static final String WHOAMI = "/v1/users/whoami";
    private static final String SUMMARY = "/v1/sports/en/sport_events/od:match:1/summary";

    @Test
    void anExemptCallIsAnsweredByTheRoutesAndTakesNoOrdinal() throws Exception {
        long seed = seedWhereFirst(fault -> fault.kind() == Kind.SERVER_ERROR);
        try (var api = FakeRestServer.start()) {
            new ChaosApi(seed, STALL).install(api);

            assertThat(get(api, WHOAMI)).isEqualTo(200);
            assertThat(get(api, "/v1/descriptions/en/markets")).isEqualTo(200);
            // the first ordinal is still to come: it is the server error the seed gave it
            assertThat(get(api, SUMMARY)).isEqualTo(503);
        }
    }

    @Test
    void aPathThatOnlyLooksLikeAnExemptOneIsNotExempt() throws Exception {
        long seed = seedWhereFirst(fault -> fault.kind() == Kind.SERVER_ERROR);
        try (var api = FakeRestServer.start()) {
            new ChaosApi(seed, STALL).install(api);

            assertThat(get(api, "/v1/descriptions/en/markets/1/variants/x")).isEqualTo(503);
        }
    }

    @Test
    void theLatencyIsWaitedOutBeforeTheAnswer() throws Exception {
        long seed = seedWhereFirst(
                fault -> fault.kind() == Kind.NONE && fault.latency().compareTo(Duration.ofMillis(100)) >= 0);
        var latency = new ChaosApi(seed, STALL).faultFor(0).latency();
        try (var api = FakeRestServer.start()) {
            new ChaosApi(seed, STALL).install(api);

            long started = System.nanoTime();
            get(api, SUMMARY);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(latency);
        }
    }

    @Test
    void anInterruptedWaitStillAnswersAndKeepsTheInterrupt() {
        long seed = seedWhereFirst(
                fault -> fault.kind() == Kind.SERVER_ERROR && fault.latency().isPositive());
        var chaos = new ChaosApi(seed, STALL);

        Thread.currentThread().interrupt();
        try {
            var reply = chaos.reply(new RecordedRequest("GET", SUMMARY, null, Map.of()));

            assertThat(reply.status()).isEqualTo(503);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt flag")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aCutOffAnswerReachesTheClientAsAFailure() throws Exception {
        long seed = seedWhereFirst(fault -> fault.kind() == Kind.CUT_OFF);
        try (var api = FakeRestServer.start()) {
            new ChaosApi(seed, STALL).install(api);

            assertThatThrownBy(() -> get(api, SUMMARY)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void aStalledAnswerIsReleasedWhenTheClientHasGivenUp() throws Exception {
        long seed = seedWhereFirst(fault -> fault.kind() == Kind.STALL);
        try (var api = FakeRestServer.start();
                var client = client()) {
            new ChaosApi(seed, STALL).install(api);
            var request = HttpRequest.newBuilder(URI.create("https://" + api.apiHost() + SUMMARY))
                    .build();
            var answer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

            assertThatThrownBy(() -> answer.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            answer.cancel(true);

            long started = System.nanoTime();
            // the stall is 30 s: without its release this waits out the fake's limit and fails
            assertThatCode(api::awaitQuiet).doesNotThrowAnyException();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    /** The first seed whose first fault suits: seeds are plain numbers, so the search is repeatable. */
    private static long seedWhereFirst(Predicate<Fault> wanted) {
        for (long seed = 0; seed < 100_000; seed++) {
            if (wanted.test(new ChaosApi(seed, STALL).faultFor(0))) {
                return seed;
            }
        }
        throw new AssertionError("no seed gives such a first fault");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().sslContext(TestTls.clientContext()).build();
    }

    private static int get(FakeRestServer api, String path) throws IOException, InterruptedException {
        try (var client = client()) {
            var request = HttpRequest.newBuilder(URI.create("https://" + api.apiHost() + path))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }
}
