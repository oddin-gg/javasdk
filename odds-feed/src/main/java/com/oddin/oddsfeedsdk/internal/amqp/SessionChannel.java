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
 * One of the transport's channels: an exclusive queue of the broker's naming, bound with its keys,
 * and a consumer that only hands each delivery over - to a session's queue, with a prefetch and
 * manual acknowledgement, or, for the SDK's own alive consumer, to a handler, with the broker
 * acknowledging each delivery as it sends it. A channel the broker takes is opened again the same
 * way for both, and a session is told of the loss and of the new channel, since its queue lost what
 * it held. Channel changes and acknowledgements take the channel's lock, so an acknowledgement never
 * reaches a channel other than the one its delivery came from.
 */
final class SessionChannel implements SessionTransport {

    /** Where a channel's deliveries go. */
    sealed interface Sink {

        /** A session's queue, which the session acknowledges from, with this prefetch. */
        record Queued(SessionQueue queue, int prefetch) implements Sink {}

        /**
         * A handler, called on the consumer thread; what it throws is counted, not thrown on, since
         * the client would close the channel as if the SDK had, and no reopen would follow.
         */
        record Handled(Consumer<RawDelivery> handler) implements Sink {}
    }

    private final String exchange;
    private final List<String> bindings;
    private final Sink sink;
    private final int maxMessageSize;
    private final InstantSource clock;
    private final Supplier<@Nullable Connection> connection;
    private final Consumer<SessionChannel> lost;
    private final Consumer<Exception> reopenFailed;
    private final ChannelEvents told;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong skippedAcks = new AtomicLong();
    private final AtomicLong handlerFailures = new AtomicLong();
    /** Reopens that failed in a row: the transport pauses longer before each next one. */
    private final AtomicInteger failedReopens = new AtomicInteger();
    /** Whether a reopen is waiting or running, so the broker's closing and cancelling start one. */
    private final AtomicBoolean reopening = new AtomicBoolean();
    /** Changed under the lock; read without it. */
    private final AtomicLong epoch = new AtomicLong();
    /** The current channel's consumer, which knows its channel; changed under the lock. */
    private volatile @Nullable Deliveries current;
    /** Whether a loss was told and no channel has replaced the lost one yet; under the lock. */
    private boolean lossTold;

    /**
     * @param connection the transport's connection now, null while it has none
     * @param lost told when the broker closes or cancels this channel on its own, or it cannot be
     *     opened again on a live connection
     * @param reopenFailed told why each time it cannot be opened again on a live connection
     * @param told told when the broker takes the channel, and when a new one replaced it
     */
    SessionChannel(
            String exchange,
            List<String> bindings,
            Sink sink,
            int maxMessageSize,
            InstantSource clock,
            Supplier<@Nullable Connection> connection,
            Consumer<SessionChannel> lost,
            Consumer<Exception> reopenFailed,
            ChannelEvents told) {
        this.exchange = exchange;
        this.bindings = List.copyOf(bindings);
        this.sink = sink;
        this.maxMessageSize = maxMessageSize;
        this.clock = clock;
        this.connection = connection;
        this.lost = lost;
        this.reopenFailed = reopenFailed;
        this.told = told;
    }

    /** Opens the channel on {@code on}, in a new epoch, the old epoch's deliveries taken out first. */
    void open(Connection on) throws IOException {
        lock.lock();
        try {
            closeChannel();
            long next = epoch.incrementAndGet();
            removeEpochsBefore(next);
            Channel opened = on.createChannel();
            var consumer = new Deliveries(opened, next);
            String name = null;
            try {
                name = opened.queueDeclare("", false, true, true, null).getQueue();
                for (String key : bindings) {
                    opened.queueBind(name, exchange, key);
                }
                if (sink instanceof Sink.Queued(var _, var prefetch)) {
                    opened.basicQos(prefetch);
                }
                opened.basicConsume(name, sink instanceof Sink.Handled, consumer);
            } catch (IOException | RuntimeException e) {
                closeQuietly(opened);
                deleteQuietly(on, name);
                throw e;
            }
            // a cancel from here on or before marks this consumer, which isOpen reads
            current = consumer;
            failedReopens.set(0);
            if (lossTold) {
                lossTold = false;
                told.reopened();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void ack(RawDelivery delivery) {
        if (!(sink instanceof Sink.Queued)) {
            throw new IllegalStateException("the broker acknowledged a handled channel's deliveries already");
        }
        lock.lock();
        try {
            Deliveries consumer = current;
            if (delivery.epoch() != epoch.get()
                    || consumer == null
                    || !consumer.getChannel().isOpen()) {
                skippedAcks.incrementAndGet();
                return;
            }
            consumer.getChannel().basicAck(delivery.deliveryTag(), false);
        } catch (IOException | RuntimeException e) {
            // the channel went while acknowledging: the broker has let the delivery go with it
            skippedAcks.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        if (!tryReset()) {
            lost.accept(this);
        }
    }

    /**
     * The replacement sequence; false when the new channel could not be opened on a live
     * connection, for the caller to try again.
     */
    boolean tryReset() {
        lock.lock();
        try {
            Connection now = connection.get();
            if (now == null || !now.isOpen()) {
                // no connection to open one on: move the epoch on, and the reconnect opens the channel
                closeChannel();
                removeEpochsBefore(epoch.incrementAndGet());
                failedReopens.set(0);
                return true;
            }
            try {
                open(now);
                return true;
            } catch (IOException | RuntimeException e) {
                failedReopens.incrementAndGet();
                reopenFailed.accept(e);
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reopens the channel unless it is open: one opened since the loss - by a reconnect, or a reset
     * that was under way - must not be thrown away for it.
     *
     * @return whether the session has an open channel now, or no connection to open one on
     */
    boolean reopenIfLost() {
        lock.lock();
        try {
            if (isOpen()) {
                return true;
            }
            return tryReset();
        } finally {
            lock.unlock();
        }
    }

    /** Whether it has an open channel whose consumer the broker has not taken. */
    boolean isOpen() {
        Deliveries consumer = current;
        return consumer != null && !consumer.taken && consumer.getChannel().isOpen();
    }

    @Override
    public long epoch() {
        return epoch.get();
    }

    @Override
    public SessionQueue queue() {
        if (sink instanceof Sink.Queued(var queue, var _)) {
            return queue;
        }
        throw new IllegalStateException("a handled channel has no queue");
    }

    /** The channel now, for a test to have the broker close it. */
    @Nullable
    Channel channel() {
        Deliveries consumer = current;
        return consumer == null ? null : consumer.getChannel();
    }

    /** Deliveries the handler threw on. */
    long handlerFailures() {
        return handlerFailures.get();
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

    private void removeEpochsBefore(long next) {
        if (sink instanceof Sink.Queued(var queue, var _)) {
            queue.removeEpochsBefore(next);
        }
    }

    private void closeChannel() {
        Deliveries consumer = current;
        current = null;
        if (consumer != null) {
            closeQuietly(consumer.getChannel());
        }
    }

    /**
     * Deletes a queue declared for a consumer that never started: with no consumer it is never
     * deleted by itself, and outlives its channel for as long as the connection lasts.
     */
    static void deleteQuietly(Connection on, @Nullable String queue) {
        if (queue == null || !on.isOpen()) {
            return;
        }
        try {
            Channel deleting = on.createChannel();
            try {
                deleting.queueDelete(queue);
            } finally {
                closeQuietly(deleting);
            }
        } catch (IOException | RuntimeException alreadyGone) {
            // the broker drops it with the connection at the latest
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
        /** Whether the broker took this consumer or its channel: its own mark, never a later one's. */
        private volatile boolean taken;

        Deliveries(Channel channel, long epochOfChannel) {
            super(channel);
            this.epochOfChannel = epochOfChannel;
        }

        @Override
        public void handleDelivery(
                String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
            var timestamp = properties.getTimestamp();
            var delivery = new RawDelivery(
                    body.length > maxMessageSize ? null : body,
                    body.length,
                    envelope.getRoutingKey(),
                    envelope.getDeliveryTag(),
                    epochOfChannel,
                    clock.instant(),
                    timestamp == null ? null : timestamp.toInstant());
            switch (sink) {
                case Sink.Queued(var queue, var _) -> {
                    if (queue.offer(delivery) == SessionQueue.Offer.FULL) {
                        // refused and counted: acknowledged, or its prefetch credit would be gone for good
                        try {
                            getChannel().basicAck(envelope.getDeliveryTag(), false);
                        } catch (IOException | RuntimeException channelGone) {
                            // not thrown on: the client would close the channel as if the SDK had, and
                            // no reopen would follow; a channel that went tells so itself
                        }
                    }
                }
                case Sink.Handled(var handler) -> {
                    try {
                        handler.accept(delivery);
                    } catch (RuntimeException e) {
                        handlerFailures.incrementAndGet();
                    }
                }
            }
        }

        @Override
        public void handleCancel(String consumerTag) {
            // the broker cancelled the consumer: its queue is gone
            taken(epochOfChannel);
        }

        @Override
        public void handleShutdownSignal(String consumerTag, ShutdownSignalException signal) {
            // a channel the broker closed on a live connection; a lost connection is the transport's
            if (!signal.isInitiatedByApplication() && !signal.isHardError()) {
                taken(epochOfChannel);
            }
        }

        private void taken(long ofEpoch) {
            taken = true;
            if (ofEpoch == epoch.get()) {
                tellLost(ofEpoch);
                lost.accept(SessionChannel.this);
            }
        }

        /**
         * Tells the loss before anything opens a new channel: under the lock, and only while this
         * channel is still the current one, since one replaced already lost nothing more.
         */
        private void tellLost(long ofEpoch) {
            lock.lock();
            try {
                if (ofEpoch == epoch.get() && !lossTold) {
                    lossTold = true;
                    told.lost();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
