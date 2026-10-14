package com.oddin.oddsfeed.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** Refusing recovery requests one producer or one event at a time. The fake is local; no broker needed. */
class FakeRestServerRefusalTest {

    private static final String PRE = "/v1/pre/recovery/initiate_request";
    private static final String LIVE = "/v1/live/recovery/initiate_request";
    private static final String ODDS = "/v1/live/odds/events/od:match:1/initiate_request";
    private static final String STATEFUL = "/v1/live/stateful_messages/events/od:match:1/initiate_request";

    @Test
    void aProducersRecoveryIsRefusedForTheChosenNumberOfRequestsAndThenAccepted() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.refuseRecovery("pre", 409, 2);

            assertThat(post(api, PRE)).as("first").isEqualTo(409);
            assertThat(post(api, PRE)).as("second").isEqualTo(409);
            assertThat(post(api, PRE)).as("third").isEqualTo(202);
            assertThat(post(api, PRE)).as("fourth").isEqualTo(202);
            assertThat(api.recoveriesRefused()).isEqualTo(2);
        }
    }

    @Test
    void aRefusalTouchesNeitherTheOtherProducerNorARequestThatIsNotAPost() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.refuseRecovery("pre", 404, 1);

            assertThat(post(api, LIVE)).as("the other producer").isEqualTo(202);
            assertThat(get(api, PRE)).as("a GET of the same path").isEqualTo(404);
            assertThat(get(api, "/v1/users/whoami")).as("any other call").isEqualTo(200);
            assertThat(api.recoveriesRefused()).as("none refused so far").isZero();
            assertThat(post(api, PRE)).as("the producer's recovery").isEqualTo(404);
            assertThat(api.recoveriesRefused()).isEqualTo(1);
        }
    }

    @Test
    void aRefusalWithoutACountHoldsUntilTheRecoveriesAreAcceptedAgain() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.refuseRecovery("pre", 500);

            for (int i = 0; i < 5; i++) {
                assertThat(post(api, PRE)).as("request " + i).isEqualTo(500);
            }
            api.acceptRecoveries();
            assertThat(post(api, PRE)).as("once accepted again").isEqualTo(202);
        }
    }

    @Test
    void anEventsRecoveryIsRefusedWithoutTouchingTheOtherKindOfRecoveryOfItOrOtherEvents() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.refuseEventOddsRecovery("live", "od:match:1", 403, 1);
            api.refuseEventStatefulRecovery("live", "od:match:1", 404, 1);

            assertThat(post(api, "/v1/live/odds/events/od:match:2/initiate_request"))
                    .as("another event")
                    .isEqualTo(202);
            assertThat(post(api, "/v1/pre/odds/events/od:match:1/initiate_request"))
                    .as("another producer")
                    .isEqualTo(202);
            assertThat(post(api, ODDS)).as("odds recovery").isEqualTo(403);
            assertThat(post(api, ODDS)).as("odds recovery again").isEqualTo(202);
            assertThat(post(api, STATEFUL)).as("stateful recovery").isEqualTo(404);
            assertThat(post(api, STATEFUL)).as("stateful recovery again").isEqualTo(202);
            assertThat(api.recoveriesRefused()).isEqualTo(2);
        }
    }

    @Test
    void anOutageBeatsARefusalWhichKeepsItsCountAndARefusalBeatsAHookAndAnOverride() throws Exception {
        try (var api = FakeRestServer.start()) {
            api.respond(PRE, 418, "teapot");
            api.respondWith(_ -> Reply.of(503, "hook"));
            api.refuseRecovery("pre", 409, 1);

            api.startOutage(500);
            assertThat(post(api, PRE)).as("during the outage").isEqualTo(500);
            api.endOutage();
            assertThat(post(api, PRE)).as("the refusal, not yet used").isEqualTo(409);
            assertThat(post(api, PRE)).as("the hook, once it is used up").isEqualTo(503);
            api.respondWith(null);
            assertThat(post(api, PRE)).as("the override").isEqualTo(418);
        }
    }

    @Test
    void refusingNoRequestAtAllIsAMistake() {
        try (var api = FakeRestServer.start()) {
            assertThatThrownBy(() -> api.refuseRecovery("pre", 409, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.refuseEventOddsRecovery("pre", "od:match:1", 409, -2))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static int post(FakeRestServer api, String path) throws IOException, InterruptedException {
        return send(api, path, "POST");
    }

    private static int get(FakeRestServer api, String path) throws IOException, InterruptedException {
        return send(api, path, "GET");
    }

    private static int send(FakeRestServer api, String path, String method) throws IOException, InterruptedException {
        try (var client =
                HttpClient.newBuilder().sslContext(TestTls.clientContext()).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://" + api.apiHost() + path))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
