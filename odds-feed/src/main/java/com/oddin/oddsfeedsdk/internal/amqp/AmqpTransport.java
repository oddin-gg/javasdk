package com.oddin.oddsfeedsdk.internal.amqp;

import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.AddressResolver;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed's one connection to its broker: the SDK's own alive consumer, and a channel per session.
 *
 * <p>{@link #open} is all or nothing: the connection, the alive channel and every session's
 * channel, or nothing left open and an exception. After that, a connection the broker or the network
 * takes away is made again, with backoff, and every channel opened again on it in a new epoch -
 * exclusive queues do not survive their connection, so what the broker held for them is gone, and
 * recovery covers it. A refused login or virtual host ends the reconnecting only once refusals,
 * three at least, have gone on for a minute with no connection in between, since an auth backend
 * being deployed refuses for a while and then lets the same token in; a broker out of resources is
 * retried with a long pause, and reported each time. A channel the broker closes or cancels on a
 * live connection is opened again on its own, with growing pauses while that fails; its session is
 * told of the loss before that, since its queue went with it, and of the new channel once it is
 * bound.
 *
 * <p>The SDK's alive consumer is a channel like a session's, opened, lost and opened again the same
 * way; only its deliveries go to the alive handler, acknowledged by the broker as it sends them. The
 * consumer thread only hands deliveries over: to a session's queue, or to the alive handler, which
 * hands them on in turn.
 */
public final class AmqpTransport implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpTransport.class);

    /** How long close() waits for the broker to confirm an abort before it drops the socket. */
    private static final int ABORT_MILLIS = 200;

    /** How many refusals at least, over {@link #REFUSAL_WINDOW}, end the reconnecting. */
    static final int REFUSALS = 3;

    static final Duration REFUSAL_WINDOW = Duration.ofMinutes(1);

    private final AmqpSettings settings;
    private final ConnectionEvents events;
    private final String exchange;
    private final InstantSource clock;
    private final Refusals refusals;
    /** The SDK's alive channel, null for none. */
    private final @Nullable SessionChannel alive;
    /** Every channel: the alive one first, then the sessions'. */
    private final List<SessionChannel> channels = new ArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicBoolean opened = new AtomicBoolean();
    /** Whether a reconnect is under way; package-private for a test to hold one off. */
    final AtomicBoolean reconnecting = new AtomicBoolean();

    private @Nullable ExecutorService consumers;
    private volatile @Nullable Connection connection;

    private volatile boolean closed;
    private volatile boolean failed;
    /** A test's hook: runs under the lock once a connect has opened every channel, before up is told. */
    volatile Runnable afterChannelsOpen = () -> {};

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
        this(settings, exchange, events, alives, clock, REFUSAL_WINDOW);
    }

    /** With a window for refusals a test can wait out. */
    AmqpTransport(
            AmqpSettings settings,
            String exchange,
            ConnectionEvents events,
            @Nullable Consumer<RawDelivery> alives,
            InstantSource clock,
            Duration refusalWindow) {
        this.settings = settings;
        this.exchange = exchange;
        this.events = new Guarded(events);
        this.clock = clock;
        this.refusals = new Refusals(REFUSALS, refusalWindow, clock);
        this.alive = alives == null
                ? null
                : channel(
                        List.of(RoutingKeys.ALIVE),
                        new SessionChannel.Sink.Handled(alives),
                        "the SDK's alive channel",
                        ChannelEvents.NONE);
        if (alive != null) {
            channels.add(alive);
        }
    }

    /** A session's channel, opened with the others by {@link #open}. */
    public SessionTransport addSession(List<String> bindings) {
        return addSession(bindings, ChannelEvents.NONE);
    }

    /**
     * The same, telling {@code told} when the broker takes the session's channel on a live
     * connection, and when a new one replaced it.
     */
    public SessionTransport addSession(List<String> bindings, ChannelEvents told) {
        return addSession(bindings, settings.prefetch(), told);
    }

    /** With a queue of another size than the prefetch, for a test to fill it. */
    SessionTransport addSession(List<String> bindings, int queueCapacity) {
        return addSession(bindings, queueCapacity, ChannelEvents.NONE);
    }

    private SessionTransport addSession(List<String> bindings, int queueCapacity, ChannelEvents told) {
        lock.lock();
        try {
            if (connection != null || opened.get()) {
                throw new IllegalStateException("sessions are added before the transport opens");
            }
            var session = channel(
                    bindings,
                    new SessionChannel.Sink.Queued(new SessionQueue(queueCapacity), settings.prefetch()),
                    "a session's channel",
                    new GuardedChannel(told));
            channels.add(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    private SessionChannel channel(List<String> bindings, SessionChannel.Sink sink, String which, ChannelEvents told) {
        return new SessionChannel(
                exchange,
                bindings,
                sink,
                settings.maxMessageSize(),
                clock,
                () -> connection,
                this::channelLost,
                e -> reopenFailed(which, e),
                told);
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
                    channels.size() + 1,
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
            afterChannelsOpen.run();
            if (closed) {
                // close() waits for the lock; the transport it closes is not told up
                closeEverything();
                throw new InitException(
                        "Failed to open the feed: the feed was closed as it opened",
                        new IOException("closed while opening"));
            }
            // up before opened: a loss the listener then ignores is found below, and told after up
            events.up();
            opened.set(true);
            // lost between the last channel and now: its listener saw a transport not yet open
            Connection now = connection;
            if (!closed && (now == null || !now.isOpen()) && reconnecting.compareAndSet(false, true)) {
                var lost = new IOException("the connection was lost as the feed opened");
                events.down(Failure.describe(lost, settings.accessToken()));
                Thread.ofVirtual().name("oddsfeed-amqp-reconnect").start(() -> reconnect(lost));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A channel that would not open again on a live connection: logged with its cause, since the
     * connection still reads as up and nothing else would show it. It is tried again with backoff.
     */
    private void reopenFailed(String which, Exception cause) {
        LOG.warn(
                "{} could not be opened again on the live connection, and is tried again: {}",
                which,
                Failure.describe(cause, settings.accessToken()));
    }

    /** Whether the connection is there and open. */
    boolean connectionOpen() {
        Connection now = connection;
        return now != null && now.isOpen();
    }

    boolean isClosed() {
        return closed;
    }

    /** Whether reconnecting gave up for good, after refusals that went on for a minute. */
    public boolean hasFailed() {
        return failed;
    }

    @Override
    public void close() {
        closed = true;
        closing.countDown();
        // a declare or bind the broker does not answer holds the lock; cut the connection under it
        // first, so it fails at once instead of at its timeout
        Connection now = connection;
        if (now != null) {
            // briefly: a broker that does not answer the declare does not answer the close either
            now.abort(ABORT_MILLIS);
        }
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
        made.addShutdownListener(signal -> connectionLost(made, signal));
        // a close that came while connecting found no connection to cut: cut it here, and between
        // the channels, each of which can wait out a declare
        abortIfClosed(made);
        for (SessionChannel channel : channels) {
            channel.open(made);
            abortIfClosed(made);
        }
    }

    private void abortIfClosed(Connection made) throws IOException {
        if (closed) {
            made.abort(ABORT_MILLIS);
            throw new IOException("the feed was closed while its connection was being made");
        }
    }

    /** The SDK's alive channel, for a test; null for none. */
    @Nullable
    SessionChannel aliveChannel() {
        return alive;
    }

    /** Package-private for a test to see how the transport connects. */
    ConnectionFactory factory() {
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
        factory.setRequestedHeartbeat(Math.clamp(settings.heartbeat().toSeconds(), 1, Integer.MAX_VALUE));
        factory.setConnectionTimeout(millis(settings.connectTimeout()));
        factory.setHandshakeTimeout(millis(settings.connectTimeout()));
        // a declare or bind the broker never answers holds the lock, and close() with it, this long
        factory.setChannelRpcTimeout(millis(settings.connectTimeout()));
        // the client names a channel by its connection, and that by its user - the token - when it logs
        // a callback's failure; nor does it close the channel, as the default does: reopening is ours
        factory.setExceptionHandler(new RedactingExceptionHandler(settings.accessToken()));
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
    private void connectionLost(Connection lost, ShutdownSignalException signal) {
        if (signal.isInitiatedByApplication() || closed || !opened.get()) {
            return;
        }
        // decided under the lock, off the client's thread: a listener late for a connection that was
        // already replaced - by a reconnect that saw it closed first - must not take the new one down
        Thread.ofVirtual().name("oddsfeed-amqp-reconnect").start(() -> {
            boolean ours;
            lock.lock();
            try {
                ours = !closed && lost.equals(connection) && reconnecting.compareAndSet(false, true);
                if (ours) {
                    events.down(Failure.describe(signal, settings.accessToken()));
                }
            } finally {
                lock.unlock();
            }
            if (ours) {
                reconnect(signal);
            }
        });
    }

    /**
     * A channel the broker closed or cancelled on a live connection: open it again, with a growing
     * pause while that fails, until it works, the connection goes, or the transport closes. A channel
     * that is open by then - opened by a reconnect, say - is kept.
     */
    private void channelLost(SessionChannel channel) {
        if (closed || reconnecting.get() || !channel.startReopen()) {
            return;
        }
        Thread.ofVirtual().name("oddsfeed-amqp-channel").start(() -> {
            try {
                while (!pause(backoff(channel.failedReopens() + 1)) && !reconnecting.get()) {
                    if (channel.reopenIfLost()) {
                        break;
                    }
                }
            } finally {
                released(channel);
            }
        });
    }

    /**
     * Ends a reopen's claim. A loss while it was held found it held and returned, so it is taken up
     * here.
     */
    void released(SessionChannel channel) {
        channel.reopenDone();
        if (!closed && !reconnecting.get() && !channel.isOpen()) {
            channelLost(channel);
        }
    }

    /**
     * After a reconnect: a channel the broker took while the others were opening found a reconnect
     * running and was not reopened, so it is now.
     */
    void reopenWhatWasLost() {
        for (SessionChannel channel : channels) {
            if (!channel.isOpen()) {
                channelLost(channel);
            }
        }
    }

    private void reconnect(Throwable firstCause) {
        Failure failure = Failure.of(firstCause);
        Throwable cause = firstCause;
        for (int attempt = 1; !closed; attempt++) {
            if (failure == Failure.REFUSED && refusedTooOften()) {
                Throwable refused = cause;
                String observed = refusalsSoFar();
                tellUnlessClosed(() -> {
                    failed = true;
                    events.fatal(
                            "the broker refused the login " + observed + ": "
                                    + Failure.describe(refused, settings.accessToken()),
                            Failure.redacted(refused, settings.accessToken()));
                });
                return;
            }
            Duration wait = failure == Failure.RESOURCES ? settings.resourceBackoff() : backoff(attempt);
            int tries = attempt;
            String why = Failure.describe(cause, settings.accessToken());
            if (!tellUnlessClosed(() -> events.recovering(tries, wait.toMillis(), why))) {
                return;
            }
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
                afterChannelsOpen.run();
                if (closed) {
                    // closed while connecting: close() waits for the lock, but no up is told after it
                    closeConnectionQuietly();
                    return;
                }
                refusals.clear();
                events.up();
                reconnecting.set(false);
                reopenWhatWasLost();
                Connection now = connection;
                // lost again before the flag was down: its listener saw a reconnect running, so go on
                if ((now != null && now.isOpen()) || !reconnecting.compareAndSet(false, true)) {
                    return;
                }
                failure = Failure.NETWORK;
                cause = new IOException("the connection was lost again as it came up");
                // it was up: a new loss, whose backoff starts again from the first pause
                attempt = 0;
                if (closed) {
                    return;
                }
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

    /**
     * Tells an event under the lock unless the transport is closed: close() takes the lock, so once
     * it has returned nothing more is told.
     *
     * @return whether it was told
     */
    private boolean tellUnlessClosed(Runnable tell) {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            tell.run();
            return true;
        } finally {
            lock.unlock();
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

    /** What was seen of the refusals, as the fatal event says it: how many, over how long. */
    private String refusalsSoFar() {
        lock.lock();
        try {
            return refusals.count() + " times over " + refusals.span().toSeconds() + " s";
        } finally {
            lock.unlock();
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

    /** In the client's milliseconds, where 0 means no limit: a positive duration is at least 1. */
    private static int millis(Duration duration) {
        long millis = duration.toMillis();
        return Math.clamp(millis == 0 && duration.isPositive() ? 1 : millis, 0, Integer.MAX_VALUE);
    }

    private Duration backoff(int attempt) {
        long first = settings.firstBackoff().toNanos();
        long capped = Math.min(settings.maxBackoff().toNanos(), first << Math.min(attempt - 1, 20));
        return Duration.ofNanos((long) (capped * ThreadLocalRandom.current().nextDouble(0.7, 1.3)));
    }

    private String reason(Throwable failure) {
        // a certificate the checks refused, by its chain or by its host name; a handshake the network
        // cut short is an SSLException too, but a broker out of reach, not one not to be trusted
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException) {
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
        for (SessionChannel channel : channels) {
            channel.close();
        }
        closeConnectionQuietly();
        ExecutorService pool = consumers;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void closeConnectionQuietly() {
        Connection current = connection;
        connection = null;
        if (current != null) {
            try {
                if (current.isOpen()) {
                    current.close(millis(settings.connectTimeout()));
                }
            } catch (IOException | RuntimeException alreadyGone) {
                // closing is all that was wanted
            }
        }
    }

    /**
     * The listener, kept from breaking the transport: a listener that throws is logged, and the
     * transport goes on - else an exception from up would close the connection just made, and one from
     * recovering would end the reconnecting.
     */
    private static final class Guarded implements ConnectionEvents {
        private final ConnectionEvents listener;

        Guarded(ConnectionEvents listener) {
            this.listener = listener;
        }

        @Override
        public void connecting() {
            guard("connecting", listener::connecting);
        }

        @Override
        public void up() {
            guard("up", listener::up);
        }

        @Override
        public void down(String reason) {
            guard("down", () -> listener.down(reason));
        }

        @Override
        public void recovering(int attempt, long waitMillis, String reason) {
            guard("recovering", () -> listener.recovering(attempt, waitMillis, reason));
        }

        @Override
        public void fatal(String reason, @Nullable Throwable cause) {
            guard("fatal", () -> listener.fatal(reason, cause));
        }

        private static void guard(String event, Runnable tell) {
            try {
                tell.run();
            } catch (RuntimeException e) {
                LOG.error("The connection listener threw on {}; the transport goes on", event, e);
            }
        }
    }

    /**
     * A session's channel listener, kept from breaking the transport: one that throws on a loss
     * would otherwise leave the channel without its reopen.
     */
    private static final class GuardedChannel implements ChannelEvents {
        private final ChannelEvents listener;

        GuardedChannel(ChannelEvents listener) {
            this.listener = listener;
        }

        @Override
        public void lost() {
            guard("lost", listener::lost);
        }

        @Override
        public void reopened() {
            guard("reopened", listener::reopened);
        }

        private static void guard(String event, Runnable tell) {
            try {
                tell.run();
            } catch (RuntimeException e) {
                LOG.error("A session's channel listener threw on {}; the transport goes on", event, e);
            }
        }
    }
}
