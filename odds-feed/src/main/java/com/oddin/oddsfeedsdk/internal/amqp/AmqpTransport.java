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
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
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
 * retried with a long pause, and reported each time. A channel the broker closes or cancels on a
 * live connection is opened again on its own, with growing pauses while that fails.
 *
 * <p>The consumer thread only hands deliveries over: to a session's queue, or, for an alive of the
 * SDK's own consumer, to the alive handler, which hands it on in turn.
 */
public final class AmqpTransport implements AutoCloseable {

    /** How many refusals within {@link #REFUSAL_WINDOW} end the reconnecting. */
    static final int REFUSALS = 3;

    static final Duration REFUSAL_WINDOW = Duration.ofMinutes(1);

    private final AmqpSettings settings;
    private final ConnectionEvents events;
    private final @Nullable Consumer<RawDelivery> alives;
    private final String exchange;
    private final InstantSource clock;
    private final Refusals refusals;
    private final List<SessionChannel> sessions = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicBoolean aliveReopening = new AtomicBoolean();
    private final AtomicInteger aliveFailedReopens = new AtomicInteger();
    /** Whether the broker took the current alive channel or its consumer; cleared by one opening. */
    private final AtomicBoolean aliveTaken = new AtomicBoolean();

    private @Nullable ExecutorService consumers;
    private volatile @Nullable Connection connection;
    /** Changed under the lock; read without it. */
    private volatile @Nullable Channel aliveChannel;

    private volatile boolean closed;
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
        this.refusals = new Refusals(REFUSALS, REFUSAL_WINDOW, clock);
    }

    /** A session's channel, opened with the others by {@link #open}. */
    public SessionTransport addSession(List<String> bindings) {
        return addSession(bindings, settings.prefetch());
    }

    /** With a queue of another size than the prefetch, for a test to fill it. */
    SessionTransport addSession(List<String> bindings, int queueCapacity) {
        lock.lock();
        try {
            if (connection != null || opened.get()) {
                throw new IllegalStateException("sessions are added before the transport opens");
            }
            var session = new SessionChannel(
                    exchange,
                    bindings,
                    new SessionQueue(queueCapacity),
                    settings.prefetch(),
                    settings.maxMessageSize(),
                    clock,
                    () -> connection,
                    this::sessionLost);
            sessions.add(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Connects and opens the alive channel and every session's channel.
     *
     * @throws InitException when any of it fails; then nothing is left open, and nothing reconnects
     */
    public void open() {
        lock.lock();
        try {
            if (closed || connection != null || opened.get()) {
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
                closed = true;
                closeEverything();
                throw new InitException(
                        "Failed to open the feed: the broker " + settings.host() + ":" + settings.port() + " "
                                + reason(e),
                        Failure.redacted(e, settings.accessToken()));
            }
            opened.set(true);
            events.up();
            // lost between the last channel and now: its listener saw a transport not yet open
            Connection now = connection;
            if ((now == null || !now.isOpen()) && reconnecting.compareAndSet(false, true)) {
                var lost = new IOException("the connection was lost as the feed opened");
                events.down(Failure.describe(lost, settings.accessToken()));
                Thread.ofVirtual().name("oddsfeed-amqp-reconnect").start(() -> reconnect(lost));
            }
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
        Connection made = factory().newConnection(consumers, asConfigured, settings.connectionName());
        connection = made;
        made.addShutdownListener(this::connectionLost);
        if (alives != null) {
            aliveChannel = openAlive(made, alives);
            aliveTaken.set(false);
            aliveFailedReopens.set(0);
        }
        for (SessionChannel session : sessions) {
            session.open(made);
        }
    }

    private Channel openAlive(Connection on, Consumer<RawDelivery> handler) throws IOException {
        Channel channel = on.createChannel();
        String queue = null;
        try {
            queue = channel.queueDeclare("", false, true, true, null).getQueue();
            channel.queueBind(queue, exchange, RoutingKeys.ALIVE);
            channel.basicConsume(queue, true, new Alives(channel, handler));
        } catch (IOException | RuntimeException e) {
            SessionChannel.closeQuietly(channel);
            SessionChannel.deleteQuietly(on, queue);
            throw e;
        }
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
        // the context overload turns it on already; said again so no change of overload can drop it
        factory.enableHostnameVerification();
        factory.setRequestedHeartbeat((int) Math.max(1, settings.heartbeat().toSeconds()));
        factory.setConnectionTimeout((int) settings.connectTimeout().toMillis());
        factory.setHandshakeTimeout((int) settings.connectTimeout().toMillis());
        // reconnection is this class's, with its epochs and its classification of failures
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        // a body over the SDK's limit still arrives, to be counted and reported, not to end the connection
        factory.setMaxInboundMessageBodySize(
                (int) Math.min(Integer.MAX_VALUE, Math.max(64L << 20, settings.maxMessageSize() + (1L << 20))));
        Map<String, Object> properties = new HashMap<>(factory.getClientProperties());
        properties.put("SDK", "java");
        properties.put("SDK_version", SdkVersion.version());
        factory.setClientProperties(properties);
        return factory;
    }

    /** The broker or the network took the connection of an opened transport: tell, and make it again. */
    private void connectionLost(ShutdownSignalException signal) {
        if (signal.isInitiatedByApplication() || closed || !opened.get()) {
            return;
        }
        if (reconnecting.compareAndSet(false, true)) {
            events.down(Failure.describe(signal, settings.accessToken()));
            Thread.ofVirtual().name("oddsfeed-amqp-reconnect").start(() -> reconnect(signal));
        }
    }

    /**
     * A session's channel the broker closed or cancelled on a live connection: open it again, with a
     * growing pause while that fails, until it works, the connection goes, or the transport closes.
     * A channel opened since the one lost in {@code epoch} - by a reconnect - is kept.
     */
    private void sessionLost(SessionChannel session, long epoch) {
        if (closed || reconnecting.get() || !session.startReopen()) {
            return;
        }
        Thread.ofVirtual().name("oddsfeed-amqp-channel").start(() -> {
            try {
                while (!pause(backoff(session.failedReopens() + 1)) && !reconnecting.get()) {
                    if (session.reopenIfStillLost(epoch)) {
                        return;
                    }
                }
            } finally {
                session.reopenDone();
            }
        });
    }

    /**
     * The SDK's alive channel the broker closed or cancelled on a live connection: open it again,
     * unless it was replaced since by one that is open.
     */
    @SuppressWarnings("ReferenceEquality") // the very channel: each opening is its own object
    private void aliveLost(@Nullable Channel lost) {
        Consumer<RawDelivery> handler = alives;
        if (handler == null || closed || reconnecting.get() || !aliveReopening.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("oddsfeed-amqp-alive").start(() -> {
            boolean again = false;
            try {
                if (pause(backoff(aliveFailedReopens.get() + 1)) || reconnecting.get()) {
                    return;
                }
                lock.lock();
                try {
                    Connection now = connection;
                    if (closed || now == null || !now.isOpen()) {
                        return;
                    }
                    Channel old = aliveChannel;
                    if (old != null && old != lost && old.isOpen() && !aliveTaken.get()) {
                        return;
                    }
                    if (old != null) {
                        SessionChannel.closeQuietly(old);
                    }
                    aliveChannel = openAlive(now, handler);
                    aliveTaken.set(false);
                    aliveFailedReopens.set(0);
                } catch (IOException | RuntimeException e) {
                    aliveFailedReopens.incrementAndGet();
                    again = true;
                } finally {
                    lock.unlock();
                }
            } finally {
                aliveReopening.set(false);
            }
            if (again) {
                aliveLost(lost);
            }
        });
    }

    private void reconnect(Throwable firstCause) {
        Failure failure = Failure.of(firstCause);
        Throwable cause = firstCause;
        for (int attempt = 1; !closed; attempt++) {
            if (failure == Failure.REFUSED && refusedTooOften()) {
                failed = true;
                events.fatal(
                        "the broker refused the login " + REFUSALS + " times within " + REFUSAL_WINDOW.toSeconds()
                                + " s: " + Failure.describe(cause, settings.accessToken()),
                        Failure.redacted(cause, settings.accessToken()));
                return;
            }
            Duration wait = failure == Failure.RESOURCES ? settings.resourceBackoff() : backoff(attempt);
            events.recovering(attempt, wait.toMillis(), Failure.describe(cause, settings.accessToken()));
            if (pause(wait)) {
                return;
            }
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                closeConnectionQuietly();
                connectAndOpenChannels();
                if (closed) {
                    // closed while connecting: close() waits for the lock, but no up is told after it
                    closeConnectionQuietly();
                    return;
                }
                refusals.clear();
                events.up();
                reconnecting.set(false);
                // a channel the broker took while the others were opening was not reopened: do it now
                for (SessionChannel session : sessions) {
                    if (!session.isOpen()) {
                        sessionLost(session, session.epoch());
                    }
                }
                Channel alive = aliveChannel;
                if (alives != null && (alive == null || !alive.isOpen() || aliveTaken.get())) {
                    aliveLost(alive);
                }
                Connection now = connection;
                // lost again before the flag was down: its listener saw a reconnect running, so go on
                if ((now != null && now.isOpen()) || !reconnecting.compareAndSet(false, true)) {
                    return;
                }
                failure = Failure.NETWORK;
                cause = new IOException("the connection was lost again as it came up");
                events.down(Failure.describe(cause, settings.accessToken()));
            } catch (IOException | TimeoutException | RuntimeException e) {
                failure = Failure.of(e);
                cause = e;
                closeConnectionQuietly();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Waits this long, or until closed: true when closed. */
    private boolean pause(Duration wait) {
        try {
            return closing.await(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private boolean refusedTooOften() {
        lock.lock();
        try {
            return refusals.refusedTooOften();
        } finally {
            lock.unlock();
        }
    }

    private Duration backoff(int attempt) {
        long first = settings.firstBackoff().toNanos();
        long capped = Math.min(settings.maxBackoff().toNanos(), first << Math.min(attempt - 1, 20));
        return Duration.ofNanos((long) (capped * ThreadLocalRandom.current().nextDouble(0.7, 1.3)));
    }

    private String reason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return "could not be trusted: " + Failure.describe(failure, settings.accessToken());
            }
        }
        return switch (Failure.of(failure)) {
            case REFUSED ->
                "refused the login or the virtual host " + settings.virtualHost() + ": "
                        + Failure.describe(failure, settings.accessToken());
            case RESOURCES -> "is out of resources: " + Failure.describe(failure, settings.accessToken());
            case NETWORK -> "could not be reached: " + Failure.describe(failure, settings.accessToken());
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
            SessionChannel.closeQuietly(alive);
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

    /** The SDK's alive consumer: hands each alive over; tells when the broker takes the channel. */
    private final class Alives extends DefaultConsumer {
        private final Consumer<RawDelivery> handler;

        Alives(Channel channel, Consumer<RawDelivery> handler) {
            super(channel);
            this.handler = handler;
        }

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

        @Override
        public void handleCancel(String consumerTag) {
            taken();
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException signal) {
            if (!signal.isInitiatedByApplication() && !signal.isHardError()) {
                taken();
            }
        }

        @SuppressWarnings("ReferenceEquality") // the very channel: each opening is its own object
        private void taken() {
            Channel mine = getChannel();
            if (mine == aliveChannel) {
                aliveTaken.set(true);
                aliveLost(mine);
            }
        }
    }
}
