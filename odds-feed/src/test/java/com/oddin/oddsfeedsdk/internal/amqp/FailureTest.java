package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

/** What each failure counts as when reconnecting. */
class FailureTest {

    @Test
    void aRefusedLoginOrVirtualHostIsARefusal() {
        assertThat(Failure.of(new IOException(new AuthenticationFailureException("ACCESS_REFUSED"))))
                .isEqualTo(Failure.REFUSED);
        assertThat(Failure.of(closed(AMQP.ACCESS_REFUSED, "ACCESS_REFUSED - Login was refused")))
                .isEqualTo(Failure.REFUSED);
        assertThat(Failure.of(closed(AMQP.NOT_ALLOWED, "NOT_ALLOWED - vhost /oddinfeed/9 not found")))
                .isEqualTo(Failure.REFUSED);
    }

    @Test
    void aBrokerAtItsLimitIsOutOfResources() {
        assertThat(
                        Failure.of(
                                closed(
                                        AMQP.NOT_ALLOWED,
                                        "NOT_ALLOWED - access to vhost '/oddinfeed/53' refused for user 'token': connection limit (5) is reached")))
                .isEqualTo(Failure.RESOURCES);
    }

    @Test
    void aCloseDuringTheLoginIsARefusalOnlyWhenTheBrokerSaysSo() {
        // the client's "possible" refusal is any close during the login, a shutting-down broker included
        assertThat(Failure.of(new PossibleAuthenticationFailureException(
                        closed(AMQP.CONNECTION_FORCED, "CONNECTION_FORCED - broker forced connection closure"))))
                .isEqualTo(Failure.NETWORK);
        assertThat(Failure.of(new PossibleAuthenticationFailureException(
                        closed(AMQP.ACCESS_REFUSED, "ACCESS_REFUSED - Login was refused"))))
                .isEqualTo(Failure.REFUSED);
    }

    @Test
    void anythingElseIsTheNetwork() {
        assertThat(Failure.of(new ConnectException("Connection refused"))).isEqualTo(Failure.NETWORK);
        assertThat(Failure.of(closed(AMQP.CONNECTION_FORCED, "CONNECTION_FORCED")))
                .isEqualTo(Failure.NETWORK);
        assertThat(Failure.of(null)).isEqualTo(Failure.NETWORK);
    }

    @Test
    void theDescriptionIsTheBrokersReasonWhenItGaveOne() {
        assertThat(Failure.describe(new IOException(closed(AMQP.ACCESS_REFUSED, "ACCESS_REFUSED - Login was refused"))))
                .isEqualTo("403 ACCESS_REFUSED - Login was refused");
        assertThat(Failure.describe(new IOException(new ConnectException("Connection refused"))))
                .isEqualTo("Connection refused");
    }

    private static ShutdownSignalException closed(int code, String text) {
        var close = new AMQP.Connection.Close.Builder()
                .replyCode(code)
                .replyText(text)
                .build();
        return new ShutdownSignalException(true, false, close, null);
    }
}
