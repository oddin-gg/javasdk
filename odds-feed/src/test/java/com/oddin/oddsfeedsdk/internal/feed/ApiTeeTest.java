package com.oddin.oddsfeedsdk.internal.feed;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.LogCapture;
import com.oddin.oddsfeedsdk.internal.events.EventsDispatcher;
import com.oddin.oddsfeedsdk.internal.rest.ApiCall;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The REST client's events told to each of the feed's listeners in turn, and one that throws keeping
 * no other from hearing.
 */
class ApiTeeTest {

    private static final URI URI_CALLED = URI.create("https://api.invalid/v1/users/whoami");

    private final List<String> told = new ArrayList<>();

    @Test
    void eachListenerIsToldEveryEventInTurn() {
        var tee = new ApiTee(List.of(recording("events"), recording("health")));
        var decoded = new Object();

        tee.called(call(200));
        tee.refused(call(403));
        tee.received(URI_CALLED, decoded, new byte[] {'x'});

        assertThat(told)
                .containsExactly(
                        "events called 200",
                        "health called 200",
                        "events refused 403",
                        "health refused 403",
                        "events received " + decoded + " 1",
                        "health received " + decoded + " 1");
    }

    @Test
    void aListenerThatThrowsOnAnyEventIsLoggedAndKeepsNoOtherFromHearing() {
        var throwing = new ApiEvents() {
            @Override
            public void called(ApiCall call) {
                throw new IllegalStateException("broken on called");
            }

            @Override
            public void refused(ApiCall call) {
                throw new IllegalStateException("broken on refused");
            }

            @Override
            public void received(URI uri, Object decoded, byte[] body) {
                throw new IllegalStateException("broken on received");
            }
        };
        var tee = new ApiTee(List.of(throwing, recording("health")));
        var decoded = new Object();

        try (var log = LogCapture.of(ApiTee.class)) {
            tee.called(call(200));
            tee.refused(call(401));
            tee.received(URI_CALLED, decoded, new byte[0]);

            assertThat(log.lines())
                    .containsExactly(
                            "ERROR The API listener threw on called; the others are told",
                            "ERROR The API listener threw on refused; the others are told",
                            "ERROR The API listener threw on received; the others are told");
            assertThat(log.stacks())
                    .extracting(stack -> stack.lines().findFirst().orElse(""))
                    .containsExactly(
                            "java.lang.IllegalStateException: broken on called",
                            "java.lang.IllegalStateException: broken on refused",
                            "java.lang.IllegalStateException: broken on received");
        }

        assertThat(told)
                .containsExactly("health called 200", "health refused 401", "health received " + decoded + " 0");
    }

    @Test
    void theRawApiDataAndTheRefusalReachTheClientThroughTheTee() throws InterruptedException {
        var heard = new Heard();
        var events = new EventsDispatcher(heard, heard, id -> null, id -> null);
        try {
            events.start();
            var tee = new ApiTee(List.of(events, recording("health")));
            var decoded = new Object();

            tee.received(URI_CALLED, decoded, new byte[] {'<', 'x', '/', '>'});
            tee.refused(call(403));

            // the control and the telemetry queue are two lines: no order between them
            var all = List.of(heard.next(), heard.next(), heard.next());
            assertThat(all)
                    .containsExactlyInAnyOrder(
                            "onRawApiDataReceived " + URI_CALLED + " " + decoded,
                            "onRawApiDataBytes " + URI_CALLED + " <x/>",
                            "onFatalError The API refused the access token: GET " + URI_CALLED + " answered 403");
        } finally {
            events.close();
        }
    }

    private static ApiCall call(int status) {
        return new ApiCall("GET", URI_CALLED, status, Duration.ZERO, 1, null);
    }

    private ApiEvents recording(String name) {
        return new ApiEvents() {
            @Override
            public void called(ApiCall call) {
                told.add(name + " called " + call.status());
            }

            @Override
            public void refused(ApiCall call) {
                told.add(name + " refused " + call.status());
            }

            @Override
            public void received(URI uri, Object decoded, byte[] body) {
                told.add(name + " received " + decoded + " " + body.length);
            }
        };
    }

    /** What the client heard, in order. */
    private static final class Heard implements GlobalEventsListener, OddsFeedExtListener {
        private final BlockingQueue<String> heard = new LinkedBlockingQueue<>();

        String next() throws InterruptedException {
            return requireNonNull(heard.poll(10, TimeUnit.SECONDS), "an event within 10 s");
        }

        @Override
        public void onFatalError(String reason, @Nullable Throwable cause) {
            heard.add("onFatalError " + reason);
        }

        @Override
        public void onRawApiDataReceived(URI uri, Object data) {
            heard.add("onRawApiDataReceived " + uri + " " + data);
        }

        @Override
        public void onRawApiDataBytes(URI uri, byte[] body) {
            heard.add("onRawApiDataBytes " + uri + " " + new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void onRawFeedMessageReceived(
                UnparsedMessage message,
                MessageInterest messageInterest,
                RoutingKeyInfo routingKey,
                MessageTimestamp timestamp) {}

        @Override
        public void onProducerStatusChange(ProducerStatus producerStatus) {}

        @Override
        public void onConnectionDown() {}

        @Override
        public void onEventRecoveryCompleted(URN eventId, long requestId) {}
    }
}
