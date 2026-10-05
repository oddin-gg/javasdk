package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;

/**
 * Which channel shutdowns are a lost channel. The broker's own close of a channel is seen against
 * the real broker in {@link AmqpTransportTest}; these are the ones a test cannot make it send.
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
}
