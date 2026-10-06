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
import java.util.concurrent.atomic.AtomicInteger;
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
 * hand-off never waits. The producers' own pace, an alive each every few seconds, keeps the queue
 * near empty, and the actor keeps one slot per producer of them in turn. The queue is bounded all the
 * same, at {@value #CAPACITY} alives and {@value #BYTES} bytes of them, since the channel acknowledges
 * on delivery and what is on it is not the SDK's to pace: a wedged thread, or a flood of what is no
 * alive, would otherwise fill the heap. An alive with no room is dropped and counted. That costs the
 * actor a beat, not a wrong state: a producer that stays unsubscribed says so in every alive until a
 * recovery is asked for, and one whose alives stop for longer than the maximum inactivity is taken
 * down, never kept up. An alive that does not decode, or a message on the channel that is no alive,
 * is counted and logged.
 *
 * <p>Safe for concurrent use.
 */
public final class AliveDispatcher implements Consumer<RawDelivery>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AliveDispatcher.class);

    /** How long the thread waits for an alive before it looks again, should a wake-up be missed. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long close() waits for the thread to end. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    /** The most alives queued: many minutes of every producer's alives, so a thread that far behind is wedged. */
    static final int CAPACITY = 1_000;
    /**
     * The most bytes of alives queued. An alive is a couple of hundred bytes, so {@link #CAPACITY} of
     * them fit; what the bound stops is a flood of large bodies, each within the maximum message size.
     */
    static final long BYTES = 1 << 20;

    private final FeedDecoder decoder;
    private final ClockOffsets offsets;
    private final AliveFacts actor;
    private final Queue<RawDelivery> alives = new ConcurrentLinkedQueue<>();
    /** How many alives are queued. */
    private final AtomicInteger size = new AtomicInteger();
    /** The bytes of the bodies of the alives queued. */
    private final AtomicLong bytes = new AtomicLong();

    private final AtomicLong dropped = new AtomicLong();
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

    /** An alive from the transport's consumer thread: queued, never waited for; dropped when there is no room. */
    @Override
    public void accept(RawDelivery alive) {
        if (closed) {
            return;
        }
        long length = length(alive);
        int queued = size.incrementAndGet();
        long held = bytes.addAndGet(length);
        if (queued > CAPACITY || held > BYTES) {
            release(length);
            long count = dropped.incrementAndGet();
            // the first, then one in a thousand: a full queue is a wedged thread, and the watchdog says so
            if (count == 1 || count % 1_000 == 0) {
                LOG.warn("The alive queue is full; an alive on {} is dropped, {} so far", alive.routingKey(), count);
            }
            return;
        }
        alives.add(alive);
        LockSupport.unpark(thread);
    }

    /** Alives queued and not handled yet; for the watchdog. */
    public int queued() {
        return size.get();
    }

    /** Alives dropped for want of room in the queue. */
    public long dropped() {
        return dropped.get();
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
        stop();
        awaitStop(System.nanoTime() + CLOSE_WAIT.toNanos());
    }

    /** Tells the thread to stop, and returns at once; nothing is queued from now on. */
    public void stop() {
        closed = true;
        LockSupport.unpark(thread);
    }

    /**
     * Waits for the thread to end after a {@link #stop}, until {@code deadline}, by {@link
     * System#nanoTime}, then drops the alives still queued; says so in the log when it does not end.
     *
     * @return whether the thread has ended
     */
    public boolean awaitStop(long deadline) {
        try {
            if (!thread.isAlive() || thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alives.clear();
        }
        if (!thread.isAlive()) {
            return true;
        }
        LOG.warn("The alive dispatcher did not stop in time");
        return false;
    }

    private void run() {
        while (!closed) {
            RawDelivery alive = alives.poll();
            if (alive == null) {
                LockSupport.parkNanos(this, IDLE_NANOS);
                continue;
            }
            release(length(alive));
            try {
                handle(alive);
            } catch (RuntimeException | Error e) {
                // one bad alive must not end the liveness of every producer
                unreadable(alive, e);
            }
            handled.incrementAndGet();
        }
    }

    /** Gives back the room of an alive no longer queued, or one that found none. */
    private void release(long length) {
        size.decrementAndGet();
        bytes.addAndGet(-length);
    }

    private static long length(RawDelivery delivery) {
        byte[] body = delivery.body();
        return body == null ? 0 : body.length;
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
