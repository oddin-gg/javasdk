package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.AddressResolver;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * The feed's one connection to its broker: the SDK's own alive consumer, and a channel per session.
 *
 * <p>{@link #open} is all or nothing: the connection, the alive channel and every session's
 * channel, or nothing left open and an exception. After that, a connection the broker or the network
 * takes away is made again, with backoff, and every channel opened again on it in a new epoch -
 * exclusive queues do not survive their connection, so what the broker held for them is gone, and
 * recovery covers it. A refused login or virtual host ends the reconnecting after three within a
 * minute, since one alone can be a blip of the broker's auth backend; a broker out of resources is
 * retried with a long pause, and reported each time.
 *
 * <p>The consumer thread only hands deliveries over: to a session's queue, or, for an alive of the
 * SDK's own consumer, to the alive handler, which hands it on in turn.
 */
public final class AmqpTransport implements AutoCloseable {

    /** How many refusals in a row, within {@link #REFUSAL_WINDOW}, end the reconnecting. */
    static final int REFUSALS = 3;

    static final Duration REFUSAL_WINDOW = Duration.ofMinutes(1);

    private final AmqpSettings settings;
    private final ConnectionEvents events;
    private final @Nullable Consumer<RawDelivery> alives;
    private final String exchange;
    private final InstantSource clock;
    private final List<SessionChannel> sessions = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closing = new CountDownLatch(1);
    private final Deque<Instant> refusals = new ArrayDeque<>();
    private @Nullable ExecutorService consumers;
    private volatile @Nullable Connection connection;
    private @Nullable Channel aliveChannel;
    private volatile boolean closed;
    private volatile boolean reconnecting;
    private volatile boolean failed;

    /**
     * @param exchange the feed's exchange, or the replay one
     * @param alives where the SDK's own alive consumer hands alives; null for none, as for replay
     */
    public AmqpTransport(
            AmqpSettings settings, String exchange, ConnectionEvents events, @Nullable Consumer<RawDelivery> alives) {
        this(settings, exchange, events, alives, InstantSource.system());
    }

    AmqpTransport(
            AmqpSettings settings,
            String exchange,
            ConnectionEvents events,
            @Nullable Consumer<RawDelivery> alives,
            InstantSource clock) {
        this.settings = settings;
        this.exchange = exchange;
        this.events = events;
        this.alives = alives;
        this.clock = clock;
    }

    /** A session's channel, opened with the others by {@link #open}. */
    public SessionTransport addSession(List<String> bindings) {
        lock.lock();
        try {
            if (connection != null) {
                throw new IllegalStateException("sessions are added before the transport opens");
            }
            var session = new SessionChannel(
                    exchange,
                    bindings,
                    new SessionQueue(settings.prefetch()),
                    settings.prefetch(),
                    settings.maxMessageSize(),
                    clock,
                    () -> connection,
                    this::lost);
            sessions.add(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Connects and opens the alive channel and every session's channel.
     *
     * @throws InitException when any of it fails; then nothing is left open
     */
    public void open() {
        lock.lock();
        try {
            if (closed || connection != null) {
                throw new IllegalStateException("the transport opens once");
            }
            consumers = Executors.newFixedThreadPool(
                    sessions.size() + 1,
                    Thread.ofPlatform()
                            .daemon()
                            .name("oddsfeed-amqp-consumer-", 0)
                            .factory());
            events.connecting();
            try {
                connectAndOpenChannels();
            } catch (IOException | TimeoutException | RuntimeException e) {
                closeEverything();
                throw new InitException(
                        "Failed to open the feed: the broker " + settings.host() + ":" + settings.port() + " "
                                + reason(e),
                        e);
            }
            events.up();
        } finally {
            lock.unlock();
        }
    }

    /** Whether reconnecting gave up for good, after three refused logins. */
    public boolean hasFailed() {
        return failed;
    }

    @Override
    public void close() {
        closed = true;
        closing.countDown();
        lock.lock();
        try {
            closeEverything();
        } finally {
            lock.unlock();
        }
    }

    private void connectAndOpenChannels() throws IOException, TimeoutException {
        // the host as configured, once: the client's own resolver tries every address of the host, and a
        // login refused on the first is then reported as whatever the last said - a network failure
        AddressResolver asConfigured = () -> List.of(new Address(settings.host(), settings.port()));
        Connection opened = factory().newConnection(consumers, asConfigured, settings.connectionName());
        connection = opened;
        opened.addShutdownListener(this::connectionLost);
        if (alives != null) {
            aliveChannel = openAlive(opened, alives);
        }
        for (SessionChannel session : sessions) {
            session.open(opened);
        }
    }

    private Channel openAlive(Connection on, Consumer<RawDelivery> handler) throws IOException {
        Channel channel = on.createChannel();
        String queue = channel.queueDeclare("", false, true, true, null).getQueue();
        channel.queueBind(queue, exchange, RoutingKeys.ALIVE);
        channel.basicConsume(queue, true, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                    String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
                var timestamp = properties.getTimestamp();
                handler.accept(new RawDelivery(
                        body.length > settings.maxMessageSize() ? null : body,
                        body.length,
                        envelope.getRoutingKey(),
                        envelope.getDeliveryTag(),
                        0,
                        clock.instant(),
                        timestamp == null ? null : timestamp.toInstant()));
            }
        });
        return channel;
    }

    private ConnectionFactory factory() {
        var factory = new ConnectionFactory();
        factory.setHost(settings.host());
        factory.setPort(settings.port());
        factory.setVirtualHost(settings.virtualHost());
        factory.setUsername(settings.accessToken());
        factory.setPassword("");
        try {
            factory.useSslProtocol(settings.tls() != null ? settings.tls() : SSLContext.getDefault());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no TLS in this JVM", e);
        }
        factory.enableHostnameVerification();
        factory.setRequestedHeartbeat((int) Math.max(1, settings.heartbeat().toSeconds()));
        factory.setConnectionTimeout((int) settings.connectTimeout().toMillis());
        factory.setHandshakeTimeout((int) settings.connectTimeout().toMillis());
        // reconnection is this class's, with its epochs and its classification of failures
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        // a body over the SDK's limit still arrives, to be counted and reported, not to end the connection
        factory.setMaxInboundMessageBodySize(Math.max(64 << 20, settings.maxMessageSize() + (1 << 20)));
        Map<String, Object> properties = new HashMap<>(factory.getClientProperties());
        properties.put("SDK", "java");
        properties.put("SDK_version", SdkVersion.version());
        factory.setClientProperties(properties);
        return factory;
    }

    /** The broker or the network took the connection: tell, and make it again. */
    private void connectionLost(ShutdownSignalException signal) {
        if (signal.isInitiatedByApplication() || closed || reconnecting) {
            return;
        }
        reconnecting = true;
        events.down(Failure.describe(signal));
        Thread.ofVirtual().name("oddsfeed-amqp-reconnect").start(() -> reconnect(Failure.of(signal), signal));
    }

    /** A session's channel the broker closed or cancelled on a live connection: open it again. */
    private void lost(SessionChannel session) {
        if (closed || reconnecting) {
            return;
        }
        Thread.ofVirtual().name("oddsfeed-amqp-channel").start(session::reset);
    }

    private void reconnect(Failure first, Throwable firstCause) {
        Failure failure = first;
        Throwable cause = firstCause;
        try {
            for (int attempt = 1; !closed; attempt++) {
                if (failure == Failure.REFUSED && refusedTooOften()) {
                    failed = true;
                    events.fatal(
                            "the broker refused the login " + REFUSALS + " times within " + REFUSAL_WINDOW.toSeconds()
                                    + " s: " + Failure.describe(cause),
                            cause);
                    return;
                }
                Duration wait = failure == Failure.RESOURCES ? settings.resourceBackoff() : backoff(attempt);
                events.recovering(attempt, wait.toMillis());
                if (closing.await(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                    return;
                }
                lock.lock();
                try {
                    if (closed) {
                        return;
                    }
                    closeConnectionQuietly();
                    connectAndOpenChannels();
                    refusals.clear();
                    events.up();
                    return;
                } catch (IOException | TimeoutException | RuntimeException e) {
                    failure = Failure.of(e);
                    cause = e;
                    closeConnectionQuietly();
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            reconnecting = false;
        }
    }

    /** Records a refusal; true once there were {@link #REFUSALS} within {@link #REFUSAL_WINDOW}. */
    private boolean refusedTooOften() {
        Instant now = clock.instant();
        refusals.addLast(now);
        while (!refusals.isEmpty() && refusals.peekFirst().isBefore(now.minus(REFUSAL_WINDOW))) {
            refusals.removeFirst();
        }
        return refusals.size() >= REFUSALS;
    }

    private Duration backoff(int attempt) {
        long first = settings.firstBackoff().toNanos();
        long capped = Math.min(settings.maxBackoff().toNanos(), first << Math.min(attempt - 1, 20));
        return Duration.ofNanos((long) (capped * ThreadLocalRandom.current().nextDouble(0.7, 1.3)));
    }

    private String reason(Throwable failure) {
        return switch (Failure.of(failure)) {
            case REFUSED ->
                "refused the login or the virtual host " + settings.virtualHost() + ": " + Failure.describe(failure);
            case RESOURCES -> "is out of resources: " + Failure.describe(failure);
            case NETWORK -> "could not be reached: " + Failure.describe(failure);
        };
    }

    private void closeEverything() {
        for (SessionChannel session : sessions) {
            session.close();
        }
        closeConnectionQuietly();
        ExecutorService pool = consumers;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void closeConnectionQuietly() {
        Channel alive = aliveChannel;
        aliveChannel = null;
        if (alive != null) {
            try {
                if (alive.isOpen()) {
                    alive.close();
                }
            } catch (IOException | TimeoutException | RuntimeException alreadyGone) {
                // closing is all that was wanted
            }
        }
        Connection current = connection;
        connection = null;
        if (current != null) {
            try {
                if (current.isOpen()) {
                    current.close((int) settings.connectTimeout().toMillis());
                }
            } catch (IOException | RuntimeException alreadyGone) {
                // closing is all that was wanted
            }
        }
    }
}
