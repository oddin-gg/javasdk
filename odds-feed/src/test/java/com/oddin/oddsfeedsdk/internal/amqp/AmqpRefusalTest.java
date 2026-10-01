package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A broker that refuses the login: at open, and over and over while reconnecting, which ends only
 * once the refusals have gone on for the whole window - here two seconds, for the test to wait out.
 */
class AmqpRefusalTest {

    private static final Duration WINDOW = Duration.ofSeconds(2);

    @Test
    void aConnectionThatCameUpInBetweenStartsTheRefusalsAgain() throws Exception {
        try (FakeFeed feed = FakeFeed.start()) {
            var events = new Recorded();
            try (var transport = transport(feed, events)) {
                transport.open();

                // two refusals, then the connection comes up again - and the window passes
                long firstPhase = System.nanoTime();
                feed.refuseVirtualHost();
                feed.closeConnections();
                awaitRefusals(feed, 2);
                feed.allowLogins();
                events.await(event -> event.equals("up") && events.count("up") == 2, Duration.ofSeconds(30));
                Thread.sleep(Math.max(0, WINDOW.toMillis() + 500 - (System.nanoTime() - firstPhase) / 1_000_000));

                // refused again: counted from now, not from the refusals before the connection came up,
                // which would end it at the first refusal
                feed.refuseVirtualHost();
                feed.closeConnections();
                events.await(event -> event.startsWith("fatal"), Duration.ofSeconds(30));
                assertThat(recoveringSinceTheLastUp(events))
                        .as("tries since the connection was up, until their refusals went on for the window")
                        .isGreaterThanOrEqualTo(4);
                assertThat(transport.hasFailed()).isTrue();
            }
        }
    }

    private static AmqpTransport transport(FakeFeed feed, Recorded events) {
        var transport = new AmqpTransport(
                AmqpTransportTest.settings(feed, 10, 1 << 20),
                FakeFeed.EXCHANGE,
                events,
                null,
                InstantSource.system(),
                WINDOW);
        transport.addSession(RoutingKeys.forSession(MessageInterest.ALL, List.of(), null, true));
        return transport;
    }

    /**
     * The tries since the connection was last up: the one after the loss, then one after each
     * refusal that did not end it. With backoffs from 100 ms, three refusals come within a second,
     * and a window of two seconds takes four at least.
     */
    private static long recoveringSinceTheLastUp(Recorded events) {
        List<String> told = List.copyOf(events.events);
        return told.subList(told.lastIndexOf("up") + 1, told.size()).stream()
                .filter("recovering"::equals)
                .count();
    }

    private static void awaitRefusals(FakeFeed feed, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (feed.refusedLogins().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(feed.refusedLogins()).hasSizeGreaterThanOrEqualTo(count);
    }

    @Test
    void refusalsThatGoOnForTheWindowEndTheReconnectingAndOneAtOpenFailsIt() throws Exception {
        try (FakeFeed feed = FakeFeed.start()) {
            var events = new Recorded();
            try (var transport = transport(feed, events)) {
                transport.open();

                // the virtual host refused: the broker's reason quotes the user, which is the token
                feed.refuseVirtualHost();
                feed.closeConnections();
                events.await(event -> event.startsWith("fatal"), Duration.ofSeconds(30));
                assertThat(recoveringSinceTheLastUp(events))
                        .as("not at the third refusal, within a second: once they went on for the window")
                        .isGreaterThanOrEqualTo(4);
                assertThat(transport.hasFailed()).isTrue();
                assertThat(events.events)
                        .anySatisfy(event -> assertThat(event)
                                .matches("fatal: the broker refused the login \\d+ times over [2-9] s: .*"));
                assertThat(events.reasons.getFirst())
                        .as("why the first try came")
                        .contains("closed by the fake feed");
                assertThat(events.reasons.subList(1, events.reasons.size()))
                        .as("why the next ones did")
                        .hasSizeGreaterThanOrEqualTo(3)
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
                int refused = feed.refusedLogins().size();
                Thread.sleep(1_000);
                assertThat(feed.refusedLogins())
                        .as("no more tries after the end")
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
