package com.oddin.oddsfeed.benchmarks;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;

/**
 * Publishes feed messages to the fake feed's broker over AMQP, for load the fake feed's own
 * publisher - the broker's management API, one HTTP request a message, about 200 odds changes a
 * second - cannot make. A connection and a channel of its own, so one instance serves one thread.
 *
 * <p>The broker is the fake feed's, which lets the login in the user the fake keeps for its own
 * publishing. This class is here, not in the fakes, because the fakes add nothing to the classpath
 * of the SDK under test: the AMQP client is the SDK's own, whichever line that is.
 */
public final class AmqpPublisher implements AutoCloseable {

    /** The user the fake feed publishes as; the broker's auth backend lets it in without recording a login. */
    private static final String USER = "fake-feed-publisher";

    private final Connection connection;
    private final Channel channel;
    private final String exchange;

    /**
     * @param tls what trusts the broker's certificate
     */
    public AmqpPublisher(String host, int port, String virtualHost, String exchange, SSLContext tls)
            throws IOException, TimeoutException {
        var factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setVirtualHost(virtualHost);
        factory.setUsername(USER);
        factory.setPassword(USER);
        factory.useSslProtocol(tls);
        this.exchange = exchange;
        this.connection = factory.newConnection("load publisher");
        this.channel = connection.createChannel();
    }

    /**
     * Publishes the message as given, with this routing key and its AMQP timestamp set to now.
     * Nothing says whether it was routed.
     */
    public void publish(String routingKey, String message) throws IOException {
        var properties = new AMQP.BasicProperties.Builder()
                .contentType("application/xml")
                .timestamp(new Date())
                .build();
        channel.basicPublish(exchange, routingKey, properties, message.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
