package com.oddin.oddsfeedsdk.internal.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One session's channel: an exclusive queue of the broker's naming, bound with the session's keys,
 * a prefetch, manual acknowledgement, and a consumer that only hands each delivery to the session's
 * queue. Channel changes and acknowledgements take the session's lock, so an acknowledgement never
 * reaches a channel other than the one its delivery came from.
 */
final class SessionChannel implements SessionTransport {

    private final String exchange;
    private final List<String> bindings;
    private final SessionQueue queue;
    private final int prefetch;
    private final int maxMessageSize;
    private final InstantSource clock;
    private final Supplier<@Nullable Connection> connection;
    private final Consumer<SessionChannel> lost;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong skippedAcks = new AtomicLong();
    /** Reopens that failed in a row: the transport pauses longer before each next one. */
    private final AtomicInteger failedReopens = new AtomicInteger();
    /** Whether a reopen is waiting or running, so the broker's closing and cancelling start one. */
    private final AtomicBoolean reopening = new AtomicBoolean();
    /** Changed under the lock; read without it. */
    private final AtomicLong epoch = new AtomicLong();

    private @Nullable Channel channel;

    /**
     * @param connection the transport's connection now, null while it has none
     * @param lost told when the broker closes or cancels this channel on its own, or it cannot be
     *     opened again on a live connection
     */
    SessionChannel(
            String exchange,
            List<String> bindings,
            SessionQueue queue,
            int prefetch,
            int maxMessageSize,
            InstantSource clock,
            Supplier<@Nullable Connection> connection,
            Consumer<SessionChannel> lost) {
        this.exchange = exchange;
        this.bindings = List.copyOf(bindings);
        this.queue = queue;
        this.prefetch = prefetch;
        this.maxMessageSize = maxMessageSize;
        this.clock = clock;
        this.connection = connection;
        this.lost = lost;
    }

    /** Opens the channel on {@code on}, in a new epoch, the old epoch's deliveries taken out first. */
    void open(Connection on) throws IOException {
        lock.lock();
        try {
            closeChannel();
            long next = epoch.incrementAndGet();
            queue.removeEpochsBefore(next);
            Channel opened = on.createChannel();
            try {
                String name = opened.queueDeclare("", false, true, true, null).getQueue();
                for (String key : bindings) {
                    opened.queueBind(name, exchange, key);
                }
                opened.basicQos(prefetch);
                opened.basicConsume(name, false, new Deliveries(opened, next));
            } catch (IOException | RuntimeException e) {
                closeQuietly(opened);
                throw e;
            }
            channel = opened;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void ack(RawDelivery delivery) {
        lock.lock();
        try {
            Channel current = channel;
            if (delivery.epoch() != epoch.get() || current == null || !current.isOpen()) {
                skippedAcks.incrementAndGet();
                return;
            }
            current.basicAck(delivery.deliveryTag(), false);
        } catch (IOException | RuntimeException e) {
            // the channel went while acknowledging: the broker has let the delivery go with it
            skippedAcks.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            Connection now = connection.get();
            if (now == null || !now.isOpen()) {
                // no connection to open one on: move the epoch on, and the reconnect opens the channel
                closeChannel();
                queue.removeEpochsBefore(epoch.incrementAndGet());
                return;
            }
            try {
                open(now);
                failedReopens.set(0);
            } catch (IOException | RuntimeException e) {
                failedReopens.incrementAndGet();
                lost.accept(this);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long epoch() {
        return epoch.get();
    }

    @Override
    public SessionQueue queue() {
        return queue;
    }

    /** Reopens that failed in a row. */
    int failedReopens() {
        return failedReopens.get();
    }

    /** Claims the one reopen that may wait or run; false when one already does. */
    boolean startReopen() {
        return reopening.compareAndSet(false, true);
    }

    void reopenDone() {
        reopening.set(false);
    }

    /** Acknowledgements skipped because their channel was gone. */
    long skippedAcks() {
        return skippedAcks.get();
    }

    void close() {
        lock.lock();
        try {
            closeChannel();
        } finally {
            lock.unlock();
        }
    }

    private void closeChannel() {
        Channel current = channel;
        channel = null;
        if (current != null) {
            closeQuietly(current);
        }
    }

    static void closeQuietly(Channel closing) {
        try {
            if (closing.isOpen()) {
                closing.close();
            }
        } catch (IOException | TimeoutException | RuntimeException alreadyGone) {
            // closing is all that was wanted
        }
    }

    /** Hands each delivery over and returns; runs on the connection's consumer thread. */
    private final class Deliveries extends DefaultConsumer {
        private final long epochOfChannel;

        Deliveries(Channel channel, long epochOfChannel) {
            super(channel);
            this.epochOfChannel = epochOfChannel;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                throws IOException {
            var timestamp = properties.getTimestamp();
            boolean queued = queue.offer(new RawDelivery(
                    body.length > maxMessageSize ? null : body,
                    body.length,
                    envelope.getRoutingKey(),
                    envelope.getDeliveryTag(),
                    epochOfChannel,
                    clock.instant(),
                    timestamp == null ? null : timestamp.toInstant()));
            if (!queued) {
                // dropped and counted: acknowledged, or its prefetch credit would be gone for good
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            }
        }

        @Override
        public void handleCancel(String consumerTag) {
            // the broker cancelled the consumer: its queue is gone
            lost.accept(SessionChannel.this);
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException signal) {
            // a channel the broker closed on a live connection; a lost connection is the transport's
            if (!signal.isInitiatedByApplication() && !signal.isHardError() && epochOfChannel == epoch.get()) {
                lost.accept(SessionChannel.this);
            }
        }
    }
}
