package com.oddin.oddsfeed.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The per-request reply hook. The fake is local; no broker needed. */
class FakeRestServerHookTest {

    private static final String WHOAMI = "/v1/users/whoami";

    @Test
    void aHookReplyAnswersAndNullLeavesTheRequestToTheRoutes() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respondWith(request -> request.path().equals(WHOAMI) ? Reply.of(503, "down") : null);

            assertThat(get(api, WHOAMI)).isEqualTo(503);
            assertThat(get(api, "/v1/descriptions/producers")).isEqualTo(200);
            assertThat(get(api, "/v1/nowhere")).isEqualTo(404);
        }
    }

    @Test
    void aHookBeatsAnOverrideOfTheSamePathButAnOutageBeatsTheHook() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(WHOAMI, 418, "teapot");
            api.respondWith(_ -> Reply.of(503, "hook"));
            assertThat(get(api, WHOAMI)).isEqualTo(503);

            api.startOutage(500);
            assertThat(get(api, WHOAMI)).isEqualTo(500);

            api.endOutage();
            api.respondWith(null);
            assertThat(get(api, WHOAMI)).isEqualTo(418);
        }
    }

    private static int get(FakeRestServer api, String path) throws IOException, InterruptedException {
        try (var client =
                HttpClient.newBuilder().sslContext(TestTls.clientContext()).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://" + api.apiHost() + path))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
