package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which channel shutdowns are a lost channel. The broker's own close of a channel is seen against
 * the real broker in {@link AmqpTransportTest}; these are the ones a test cannot make it send. And
 * the refusals of a channel's reopen, against connections that refuse it, or are lost as it is
 * opened, at the moments a test cannot pick on a real broker.
 */
class SessionChannelTest {

    @Test
    void aChannelErrorFromTheBrokerIsALostChannel() {
        var close = new AMQP.Channel.Close.Builder()
                .replyCode(AMQP.PRECONDITION_FAILED)
                .replyText("PRECONDITION_FAILED - unknown delivery tag 9999")
                .build();
        assertThat(SessionChannel.closedByTheBroker(new ShutdownSignalException(false, false, close, null)))
                .isTrue();
    }

    @Test
    void theSdksOwnCloseALostConnectionAndNoReasonAreNot() {
        var channelClose = new AMQP.Channel.Close.Builder()
                .replyCode(AMQP.REPLY_SUCCESS)
                .replyText("OK")
                .build();
        var connectionClose = new AMQP.Connection.Close.Builder()
                .replyCode(AMQP.CONNECTION_FORCED)
                .replyText("CONNECTION_FORCED - closed via management plugin")
                .build();
        assertThat(SessionChannel.closedByTheBroker(new ShutdownSignalException(false, true, channelClose, null)))
                .as("the SDK closed the channel")
                .isFalse();
        assertThat(SessionChannel.closedByTheBroker(new ShutdownSignalException(true, false, connectionClose, null)))
                .as("the connection went: the transport's to handle")
                .isFalse();
        assertThat(SessionChannel.closedByTheBroker(new ShutdownSignalException(true, true, connectionClose, null)))
                .as("the SDK closed the connection")
                .isFalse();
        assertThat(SessionChannel.closedByTheBroker(null)).as("still open").isFalse();
    }

    @Test
    void aReopenThatFailsBecauseTheConnectionWentIsNoRefusalOfTheChannel() {
        var connection = new FakeConnection();
        var channel = channelOn(connection);

        // an open connection that will not give a channel: a refusal, counted
        assertThat(channel.tryReset()).isFalse();
        assertThat(channel.refusals()).isEqualTo(1);
        assertThat(channel.streak()).isNotNull();
        assertThat(reopenFailures).hasSize(1);

        // the connection is lost as the channel is being opened: the reconnect's, nothing counted
        connection.closeOnCreate = true;
        assertThat(channel.tryReset()).as("nothing for the loop to retry").isTrue();
        assertThat(channel.refusals()).as("what was counted stays").isEqualTo(1);
        assertThat(channel.streak()).as("the connection's to tell now").isNull();
        assertThat(channel.failedReopens()).isZero();
        assertThat(reopenFailures).as("no failed reopen to log").hasSize(1);
    }

    @Test
    void aReopenThatFailsWithARuntimeExceptionIsTheSameAsOneThatFailsWithAnIoException() {
        var connection = new FakeConnection();
        connection.failure = () -> new AlreadyClosedException(new ShutdownSignalException(false, false, null, null));
        var channel = channelOn(connection);

        // an open connection whose channel call throws unchecked: a refusal, counted and logged
        assertThat(channel.tryReset()).isFalse();
        assertThat(channel.refusals()).isEqualTo(1);
        assertThat(channel.streak()).isNotNull();
        assertThat(reopenFailures).singleElement().isInstanceOf(AlreadyClosedException.class);

        // the connection is lost as the channel is being opened: the reconnect's, nothing counted
        connection.closeOnCreate = true;
        assertThat(channel.tryReset()).as("nothing for the loop to retry").isTrue();
        assertThat(channel.refusals()).as("what was counted stays").isEqualTo(1);
        assertThat(channel.streak()).as("the connection's to tell now").isNull();
        assertThat(channel.failedReopens()).isZero();
        assertThat(reopenFailures).as("no failed reopen to log").hasSize(1);
    }

    @Test
    void aRefusalOnANewConnectionStartsANewStreakFromItsOwnFirstFailure() {
        var first = new FakeConnection();
        var second = new FakeConnection();
        var channel = channelOn(first);

        channel.tryReset();
        var started = clock.monoMillis();
        clockNanos.addAndGet(10_000_000_000L);
        channel.tryReset();
        assertThat(channel.streak()).satisfies(streak -> {
            assertThat(streak.on()).isSameAs(first.connection);
            assertThat(streak.count()).isEqualTo(2);
            assertThat(streak.sinceMono()).isEqualTo(started);
        });

        // the connection replaced, the channel refused on the new one again
        current.set(second.connection);
        clockNanos.addAndGet(20_000_000_000L);
        var startedOnSecond = clock.monoMillis();
        channel.tryReset();
        assertThat(channel.streak()).satisfies(streak -> {
            assertThat(streak.on()).isSameAs(second.connection);
            assertThat(streak.count()).as("a new streak").isEqualTo(1);
            assertThat(streak.sinceMono()).as("from its own first failure").isEqualTo(startedOnSecond);
        });
        assertThat(channel.refusals()).as("all of them counted").isEqualTo(3);
    }

    private final AtomicLong clockNanos = new AtomicLong(1_000);
    private final FeedClock clock = FeedClock.of(InstantSource.system(), clockNanos::get);
    private final AtomicReference<Connection> current = new AtomicReference<>();
    private final List<Exception> reopenFailures = new ArrayList<>();

    /** A session's channel over whichever connection {@link #current} holds, starting with this one. */
    private SessionChannel channelOn(FakeConnection connection) {
        current.set(connection.connection);
        return new SessionChannel(
                "exchange",
                List.of("#"),
                new SessionChannel.Sink.Queued(new SessionQueue(10), 10, routingKey -> {}),
                1 << 20,
                clock,
                current::get,
                () -> false,
                lost -> {},
                reopenFailures::add,
                ChannelEvents.NONE);
    }

    /** A connection that is open and will not give a channel; one that is closed as it is asked, if told to. */
    // its own equals is by identity, as a connection's is
    @SuppressWarnings("ReferenceEquality")
    private static final class FakeConnection {
        private final AtomicBoolean open = new AtomicBoolean(true);
        volatile boolean closeOnCreate;
        // what the channel call throws, instead of the IOException of a refusal
        volatile @Nullable Supplier<RuntimeException> failure;
        final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "isOpen" -> open.get();
                        case "createChannel" -> {
                            if (closeOnCreate) {
                                open.set(false);
                            }
                            var unchecked = failure;
                            if (unchecked != null) {
                                throw unchecked.get();
                            }
                            throw new IOException("the channel was not given");
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "a fake connection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
