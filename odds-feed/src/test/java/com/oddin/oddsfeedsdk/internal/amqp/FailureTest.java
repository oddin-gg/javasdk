package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.PossibleAuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
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
        assertThat(Failure.describe(
                        new IOException(closed(AMQP.ACCESS_REFUSED, "ACCESS_REFUSED - Login was refused")), "a-token"))
                .isEqualTo("403 ACCESS_REFUSED - Login was refused");
        assertThat(Failure.describe(new IOException(new ConnectException("Connection refused")), "a-token"))
                .isEqualTo("Connection refused");
    }

    @Test
    void theAccessTokenTheBrokerQuotesIsTakenOut() {
        var refused = new IOException(
                closed(
                        AMQP.NOT_ALLOWED,
                        "NOT_ALLOWED - access to vhost '/oddinfeed/53' refused for user 'secret-token': connection limit (5) is reached"));
        assertThat(Failure.describe(refused, "secret-token"))
                .doesNotContain("secret-token")
                .contains("refused for user '<access token>'");
        assertThat(Failure.redacted(refused, "secret-token").getMessage()).doesNotContain("secret-token");
        assertThat(Failure.redacted(refused, "secret-token").getStackTrace()).isEqualTo(refused.getStackTrace());
    }

    @Test
    void aQueueLimitOnAChannelIsOutOfResources() {
        var close = new AMQP.Channel.Close.Builder()
                .replyCode(AMQP.PRECONDITION_FAILED)
                .replyText(
                        "PRECONDITION_FAILED - cannot declare queue: queue limit in vhost '/oddinfeed/53' (10) is reached")
                .build();
        assertThat(Failure.of(new IOException(new ShutdownSignalException(false, false, close, null))))
                .isEqualTo(Failure.RESOURCES);
    }

    @Test
    void aTokenWithLimitInItIsStillARefusal() {
        var refused = closed(
                AMQP.NOT_ALLOWED, "NOT_ALLOWED - access to vhost '/oddinfeed/53' refused for user 'unlimited-token'");
        assertThat(Failure.of(refused)).isEqualTo(Failure.REFUSED);
        var atTheLimit = closed(
                AMQP.NOT_ALLOWED,
                "NOT_ALLOWED - access to vhost '/oddinfeed/53' refused for user 'unlimited-token': connection limit"
                        + " (0) is reached");
        assertThat(Failure.of(atTheLimit)).isEqualTo(Failure.RESOURCES);
    }

    @Test
    void aChannelTheBrokerRefusesIsARefusal() {
        var close = new AMQP.Channel.Close.Builder()
                .replyCode(AMQP.ACCESS_REFUSED)
                .replyText("ACCESS_REFUSED - access to queue 'amq.gen-1' in vhost '/oddinfeed/53' refused for user"
                        + " 'secret-token'")
                .build();
        var refused = new IOException(new ShutdownSignalException(false, false, close, null));
        assertThat(Failure.of(refused)).isEqualTo(Failure.REFUSED);
        assertThat(Failure.describe(refused, "secret-token"))
                .startsWith("403 ACCESS_REFUSED")
                .doesNotContain("secret-token");
    }

    @Test
    void theSettingsDoNotPrintTheToken() {
        var settings = new AmqpSettings(
                "mq.example.invalid",
                5671,
                "/oddinfeed/53",
                "secret-token",
                null,
                "of-sdk-53-1",
                200,
                1 << 20,
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1));
        assertThat(settings.toString())
                .doesNotContain("secret-token")
                .contains("accessToken=" + Failure.TOKEN)
                .contains("virtualHost=/oddinfeed/53");
    }

    @Test
    void theFeedsSettingsComeFromItsConfiguration() {
        OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.example.invalid", "api.example.invalid")
                .setAccessToken("secret-token")
                .setAmqpPrefetch(50)
                .setMaxMessageSize(4096)
                .build();
        AmqpSettings settings = AmqpSettings.of(configuration, "/oddinfeed/53", "of-sdk-53-1");
        assertThat(settings.host()).isEqualTo("mq.example.invalid");
        assertThat(settings.port())
                .isEqualTo(configuration.getSelectedEnvironment().getMessagingPort());
        assertThat(settings.virtualHost()).isEqualTo("/oddinfeed/53");
        assertThat(settings.accessToken()).isEqualTo("secret-token");
        assertThat(settings.tls()).as("the JVM's trust").isNull();
        assertThat(settings.connectionName()).isEqualTo("of-sdk-53-1");
        assertThat(settings.prefetch()).isEqualTo(50);
        assertThat(settings.maxMessageSize()).isEqualTo(4096);
        assertThat(settings.heartbeat()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.connectTimeout()).isEqualTo(configuration.getHttpClientTimeout());
    }

    private static ShutdownSignalException closed(int code, String text) {
        var close = new AMQP.Connection.Close.Builder()
                .replyCode(code)
                .replyText(text)
                .build();
        return new ShutdownSignalException(true, false, close, null);
    }
}
