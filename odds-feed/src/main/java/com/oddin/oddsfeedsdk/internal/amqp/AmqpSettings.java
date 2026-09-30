package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * How to reach the feed's broker, and how the transport behaves when it cannot.
 *
 * @param virtualHost the one whoami names for the access token, as the Go SDK takes it
 * @param tls the trust to check the broker's certificate with, null for the JVM's default; the
 *     certificate and the host name are always checked, where 0.0.x trusted any certificate
 * @param connectionName the name the broker shows the connection by
 * @param firstBackoff the pause before the first reconnect; it doubles up to {@code maxBackoff}
 * @param resourceBackoff the pause while the broker refuses for want of resources
 */
public record AmqpSettings(
        String host,
        int port,
        String virtualHost,
        String accessToken,
        @Nullable SSLContext tls,
        String connectionName,
        int prefetch,
        int maxMessageSize,
        Duration heartbeat,
        Duration connectTimeout,
        Duration firstBackoff,
        Duration maxBackoff,
        Duration resourceBackoff) {

    /** The broker heartbeat, the Go SDK's: a dead connection shows in seconds. */
    static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** The settings with the access token left out: it is a credential, and printing it leaks it. */
    @Override
    public String toString() {
        return "AmqpSettings[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost + ", accessToken="
                + Failure.TOKEN + ", tls=" + tls + ", connectionName=" + connectionName + ", prefetch=" + prefetch
                + ", maxMessageSize=" + maxMessageSize + ", heartbeat=" + heartbeat + ", connectTimeout="
                + connectTimeout + ", firstBackoff=" + firstBackoff + ", maxBackoff=" + maxBackoff
                + ", resourceBackoff=" + resourceBackoff + "]";
    }

    /** For the feed: its environment and limits, the virtual host whoami named, a connection name. */
    public static AmqpSettings of(OddsFeedConfiguration configuration, String virtualHost, String connectionName) {
        return new AmqpSettings(
                configuration.getSelectedEnvironment().getMessagingHost(),
                configuration.getSelectedEnvironment().getMessagingPort(),
                virtualHost,
                configuration.getAccessToken(),
                null,
                connectionName,
                configuration.getAmqpPrefetch(),
                configuration.getMaxMessageSize(),
                HEARTBEAT,
                configuration.getHttpClientTimeout(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(1));
    }
}
