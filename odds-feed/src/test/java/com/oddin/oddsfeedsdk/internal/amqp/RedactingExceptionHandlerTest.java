package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the client logs of a consumer's failure, without the token it names the channel by. */
class RedactingExceptionHandlerTest {

    @Test
    void theClientsReportOfAFailedConsumerDoesNotTellTheToken() {
        List<String> logged = new ArrayList<>();
        var handler = new RedactingExceptionHandler(
                "secret-token", (message, e) -> logged.add(message + " | " + e.getMessage()));
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
