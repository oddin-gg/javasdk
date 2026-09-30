package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A broker that refuses the login: at open, and three times in a row while reconnecting. */
class AmqpRefusalTest {

    @Test
    void threeRefusalsWithinAMinuteEndTheReconnectingAndOneAtOpenFailsIt() throws Exception {
        try (FakeFeed feed = FakeFeed.start()) {
            var events = new Recorded();
            try (var transport =
                    new AmqpTransport(AmqpTransportTest.settings(feed, 10, 1 << 20), FakeFeed.EXCHANGE, events, null)) {
                transport.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
                transport.open();

                feed.refuseLogins();
                feed.closeConnections();
                events.await(
                        event -> event.startsWith("fatal: the broker refused the login 3 times"),
                        Duration.ofSeconds(30));
                assertThat(transport.hasFailed()).isTrue();
                assertThat(events.count("recovering")).isEqualTo(3);
                int refused = feed.refusedLogins().size();
                Thread.sleep(1_000);
                assertThat(feed.refusedLogins())
                        .as("no more tries after the third")
                        .hasSize(refused);
            }

            try (var refused = new AmqpTransport(
                    AmqpTransportTest.settings(feed, 10, 1 << 20), FakeFeed.EXCHANGE, new Recorded(), null)) {
                refused.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
                assertThatThrownBy(refused::open)
                        .isInstanceOf(InitException.class)
                        .hasMessageContaining("refused the login or the virtual host");
            }
            assertThat(feed.openConnections()).isEmpty();
        }
    }
}
