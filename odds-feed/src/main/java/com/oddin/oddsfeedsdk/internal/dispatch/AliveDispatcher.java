package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.internal.amqp.RawDelivery;
import com.oddin.oddsfeedsdk.internal.recovery.AliveFacts;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The alive dispatcher: one thread that decodes the alives of the SDK's own alive channel, measures
 * each producer's clock offset on them, and posts each to the recovery actor - the facts producer
 * liveness is made of. It runs no client code and calls no REST.
 *
 * <p>The transport's consumer thread hands each alive over with {@link #accept} and returns; the
 * hand-off never waits, and drops nothing: an unsubscribed alive may be the only word of a gap, and
 * what fills the queue is bounded by the producers' own pace, an alive each every few seconds. The
 * actor keeps one slot per producer of them in turn. An alive that does not decode, or a message on
 * the channel that is no alive, is counted and logged.
 *
 * <p>Safe for concurrent use.
 */
public final class AliveDispatcher implements Consumer<RawDelivery>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AliveDispatcher.class);

    /** How long the thread waits for an alive before it looks again, should a wake-up be missed. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long close() waits for the thread to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final FeedDecoder decoder;
    private final ClockOffsets offsets;
    private final AliveFacts actor;
    private final Queue<RawDelivery> alives = new ConcurrentLinkedQueue<>();
    private final AtomicLong unreadable = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private final Thread thread;
    private volatile boolean closed;

    /**
     * @param offsets where each producer's clock offset is kept, for the session dispatchers
     * @param actor the recovery actor
     */
    public AliveDispatcher(FeedDecoder decoder, ClockOffsets offsets, AliveFacts actor) {
        this.decoder = decoder;
        this.offsets = offsets;
        this.actor = actor;
        this.thread = Thread.ofPlatform().daemon().name("oddsfeed-alives").unstarted(this::run);
    }

    public void start() {
        thread.start();
    }

    /** An alive from the transport's consumer thread: queued, never waited for. */
    @Override
    public void accept(RawDelivery alive) {
        if (closed) {
            return;
        }
        alives.add(alive);
        LockSupport.unpark(thread);
    }

    /** Alives queued and not handled yet; for the watchdog. */
    public int queued() {
        return alives.size();
    }

    /** Alives handled, for the watchdog: a queue that does not move while this does not either is wedged. */
    public long handled() {
        return handled.get();
    }

    /** Deliveries on the alive channel that were no alive the SDK could read. */
    public long unreadable() {
        return unreadable.get();
    }

    @Override
    public void close() {
        closed = true;
        if (thread.isAlive()) {
            LockSupport.unpark(thread);
            try {
                if (!thread.join(CLOSE_WAIT)) {
                    LOG.warn("The alive dispatcher did not stop within {}", CLOSE_WAIT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        alives.clear();
    }

    private void run() {
        while (!closed) {
            RawDelivery alive = alives.poll();
            if (alive == null) {
                LockSupport.parkNanos(this, IDLE_NANOS);
                continue;
            }
            try {
                handle(alive);
            } catch (RuntimeException | Error e) {
                // one bad alive must not end the liveness of every producer
                unreadable(alive, e);
            }
            handled.incrementAndGet();
        }
    }

    private void handle(RawDelivery delivery) {
        byte[] body = delivery.body();
        if (body == null) {
            unreadable(
                    delivery, new IllegalArgumentException(delivery.size() + " bytes, over the maximum message size"));
            return;
        }
        UnparsedMessage message;
        try {
            message = decoder.decode(body);
        } catch (DecodeException e) {
            unreadable(delivery, e);
            return;
        }
        if (!(message instanceof OFAlive alive)) {
            unreadable(
                    delivery,
                    new IllegalArgumentException(
                            "not an alive: " + message.getClass().getSimpleName()));
            return;
        }
        long receivedAt = delivery.receivedAt().toEpochMilli();
        offsets.alive(alive.getProduct(), alive.getTimestamp(), receivedAt);
        actor.alive(alive.getProduct(), alive.getTimestamp(), receivedAt, alive.getSubscribed() == 1);
    }

    private void unreadable(RawDelivery delivery, Throwable why) {
        long count = unreadable.incrementAndGet();
        // the first, then one in a thousand: a producer sending something else would flood the log
        if (count == 1 || count % 1_000 == 0) {
            LOG.warn("An alive on {} could not be read, {} so far: {}", delivery.routingKey(), count, why.toString());
        } else {
            LOG.debug("An alive on {} could not be read", delivery.routingKey(), why);
        }
    }
}
