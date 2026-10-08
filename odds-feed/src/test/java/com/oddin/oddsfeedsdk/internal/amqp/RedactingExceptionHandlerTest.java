package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.oddin.oddsfeedsdk.LogCapture;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import java.lang.reflect.Proxy;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What the client logs of a consumer's failure, without the token it names the channel by. */
class RedactingExceptionHandlerTest {

    @Test
    void theClientsReportOfAFailedConsumerDoesNotTellTheToken() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler("secret-token", recordedIn(logged));
        handler.handleConsumerException(
                namedAs(Channel.class, "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                new IllegalStateException("the consumer of secret-token failed"),
                namedAs(Consumer.class, "alives"),
                "tag",
                "handleDelivery");
        assertThat(logged)
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .doesNotContain("secret-token")
                        .contains("amqp://" + Failure.TOKEN + "@mq.example.invalid")
                        .contains("the consumer of " + Failure.TOKEN + " failed"));
    }

    @Test
    void aSocketClosedOrResetIsOneWarningLineWithoutTheToken() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler("secret-token", recordedIn(logged));
        handler.handleConsumerException(
                namedAs(Channel.class, "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                new SocketException("Connection reset"),
                namedAs(Consumer.class, "alives"),
                "tag",
                "handleDelivery");
        assertThat(logged)
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .startsWith("WARN ")
                        .contains("Connection reset")
                        .contains("amqp://" + Failure.TOKEN + "@mq.example.invalid")
                        .doesNotContain("secret-token"));
    }

    @Test
    void aClientCallbackThatKeepsFailingIsAnErrorTheFirstAndEveryThousandthAndDebugOtherwise() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler("secret-token", recordedIn(logged));
        for (int failure = 0; failure < 1_001; failure++) {
            handler.handleConsumerException(
                    namedAs(Channel.class, "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                    new IllegalStateException("the consumer of secret-token failed"),
                    namedAs(Consumer.class, "alives"),
                    "tag",
                    "handleDelivery");
        }
        assertThat(logged.stream().filter(line -> line.startsWith("ERROR ")).count())
                .as("the first and the thousandth")
                .isEqualTo(2);
        assertThat(logged.stream().filter(line -> line.startsWith("DEBUG ")).count())
                .as("the rest")
                .isEqualTo(999);
        assertThat(logged).allSatisfy(line -> assertThat(line).doesNotContain("secret-token"));
    }

    @Test
    void theExceptionOfACallbackFailureLoggedAtDebugDoesNotTellTheToken() {
        var handler = new RedactingExceptionHandler("secret-token");
        try (var log = LogCapture.of(AmqpTransport.class, Level.DEBUG)) {
            for (int failure = 0; failure < 3; failure++) {
                handler.handleConsumerException(
                        namedAs(
                                Channel.class,
                                "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                        new IllegalStateException("the consumer of secret-token failed"),
                        namedAs(Consumer.class, "alives"),
                        "tag",
                        "handleDelivery");
            }
            assertThat(log.lines().stream()
                            .filter(line -> line.startsWith("DEBUG "))
                            .count())
                    .as("the second and the third")
                    .isEqualTo(2);
            assertThat(log.stacks())
                    .as("the exception of each line, the debug ones too")
                    .hasSize(3)
                    .allSatisfy(stack -> assertThat(stack)
                            .doesNotContain("secret-token")
                            .contains("the consumer of " + Failure.TOKEN + " failed"));
        }
    }

    @Test
    void aConnectionErrorIsStillAnErrorAfterManyCallbackFailures() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler("secret-token", recordedIn(logged));
        for (int failure = 0; failure < 5; failure++) {
            handler.handleConsumerException(
                    namedAs(Channel.class, "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                    new IllegalStateException("the consumer of secret-token failed"),
                    namedAs(Consumer.class, "alives"),
                    "tag",
                    "handleDelivery");
        }
        handler.handleUnexpectedConnectionDriverException(
                namedAs(Connection.class, "amqp://secret-token@mq.example.invalid:5671//oddinfeed/53"),
                new IllegalStateException("the driver of secret-token failed"));
        assertThat(logged.stream().filter(line -> line.startsWith("ERROR ")).toList())
                .as("the first callback failure and the connection error")
                .hasSize(2)
                .last()
                .satisfies(line -> assertThat(line)
                        .contains("An unexpected connection driver error occurred")
                        .doesNotContain("secret-token"));
    }

    @Test
    void aSocketResetRepeatedWithinAMinuteIsOneWarningAndTheRestDebug() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler("secret-token", recordedIn(logged));
        for (int reset = 0; reset < 3; reset++) {
            handler.handleConsumerException(
                    namedAs(Channel.class, "AMQChannel(amqp://secret-token@mq.example.invalid:5671//oddinfeed/53,1)"),
                    new SocketException("Connection reset"),
                    namedAs(Consumer.class, "alives"),
                    "tag",
                    "handleDelivery");
        }
        assertThat(logged.stream().filter(line -> line.startsWith("WARN ")).count())
                .as("one in the first minute")
                .isEqualTo(1);
        assertThat(logged.stream().filter(line -> line.startsWith("DEBUG ")).count())
                .as("the rest")
                .isEqualTo(2);
    }

    private static RedactingExceptionHandler.Log recordedIn(List<String> logged) {
        return new RedactingExceptionHandler.Log() {
            @Override
            public void warn(String message) {
                logged.add("WARN " + message);
            }

            @Override
            public void error(String message, Throwable e) {
                logged.add("ERROR " + message + " | " + e.getMessage());
            }

            @Override
            public void debug(String message, @Nullable Throwable e) {
                logged.add("DEBUG " + message + (e == null ? "" : " | " + e.getMessage()));
            }
        };
    }

    /** Nothing but the name the client prints it by; closing it, as the default handler would, fails. */
    private static <T> T namedAs(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) {
                return name;
            }
            throw new UnsupportedOperationException(method.getName());
        }));
    }
}
