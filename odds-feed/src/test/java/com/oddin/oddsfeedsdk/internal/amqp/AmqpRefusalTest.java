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
    void aConnectionThatCameUpInBetweenStartsTheCountOfRefusalsAgain() throws Exception {
        try (FakeFeed feed = FakeFeed.start()) {
            var events = new Recorded();
            try (var transport =
                    new AmqpTransport(AmqpTransportTest.settings(feed, 10, 1 << 20), FakeFeed.EXCHANGE, events, null)) {
                transport.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
                transport.open();

                // one refusal, then the connection comes up again
                feed.refuseVirtualHost();
                feed.closeConnections();
                awaitRefusals(feed, 1);
                feed.allowLogins();
                events.await(event -> event.equals("up") && events.count("up") == 2, Duration.ofSeconds(30));

                // refused again: the end comes with the third refusal since the connection was up, not
                // with the second, which would be the third within the minute
                int refusedBefore = feed.refusedLogins().size();
                feed.refuseVirtualHost();
                feed.closeConnections();
                events.await(event -> event.startsWith("fatal"), Duration.ofSeconds(30));
                List<String> sinceUp = events.events.subList(events.events.lastIndexOf("up") + 1, events.events.size());
                assertThat(sinceUp)
                        .as("the try after the loss, then one after each of two refusals")
                        .startsWith("down", "recovering", "recovering", "recovering")
                        .hasSize(5);
                assertThat(feed.refusedLogins().size() - refusedBefore).isEqualTo(3);
                assertThat(transport.hasFailed()).isTrue();
            }
        }
    }

    private static void awaitRefusals(FakeFeed feed, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (feed.refusedLogins().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(feed.refusedLogins()).hasSizeGreaterThanOrEqualTo(count);
    }

    @Test
    void threeRefusalsWithinAMinuteEndTheReconnectingAndOneAtOpenFailsIt() throws Exception {
        try (FakeFeed feed = FakeFeed.start()) {
            var events = new Recorded();
            try (var transport =
                    new AmqpTransport(AmqpTransportTest.settings(feed, 10, 1 << 20), FakeFeed.EXCHANGE, events, null)) {
                transport.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
                transport.open();

                // the virtual host refused: the broker's reason quotes the user, which is the token
                feed.refuseVirtualHost();
                feed.closeConnections();
                events.await(
                        event -> event.startsWith("fatal: the broker refused the login 3 times"),
                        Duration.ofSeconds(30));
                assertThat(transport.hasFailed()).isTrue();
                assertThat(events.reasons.getFirst())
                        .as("why the first try came")
                        .contains("closed by the fake feed");
                assertThat(events.reasons.subList(1, events.reasons.size()))
                        .as("why the next ones did")
                        .allSatisfy(reason ->
                                assertThat(reason).contains("NOT_ALLOWED").contains(Failure.TOKEN));
                assertThat(String.join(" ", events.told)).doesNotContain("test-token");
                assertThat(events.events)
                        .anySatisfy(
                                event -> assertThat(event).startsWith("fatal: ").contains(Failure.TOKEN));
                assertThat(events.fatalCause).isNotNull();
                var messages = new StringBuilder();
                for (Throwable cause = events.fatalCause; cause != null; cause = cause.getCause()) {
                    messages.append(cause.getMessage()).append('\n');
                }
                assertThat(messages.toString())
                        .as("the fatal cause chain")
                        .doesNotContain("test-token")
                        .contains(Failure.TOKEN);
                assertThat(events.count("recovering")).isEqualTo(3);
                int refused = feed.refusedLogins().size();
                Thread.sleep(1_000);
                assertThat(feed.refusedLogins())
                        .as("no more tries after the third")
                        .hasSize(refused);
            }

            feed.refuseLogins();
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
