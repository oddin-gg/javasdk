package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.internal.producer.Producers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * How far each producer's clock is from the SDK's, measured by the alive dispatcher on the alives of
 * the SDK's own alive channel, which no backlog delays: when the SDK received an alive less when the
 * producer stamped it. The session dispatchers read it to tell how old a message is by the
 * producer's own clock - a message older than the match status age writes no live state (KD-14).
 *
 * <p>The recovery actor measures the same on the same alives for its safety net; this copy is the
 * dispatchers', so reading it waits for no actor. Only the producers of the producer list have an
 * offset: an alive of any other is not kept, as the actor does not keep it, so whatever ids the feed
 * sends, the map holds one entry per producer of the list at most. Safe for concurrent use.
 */
public final class ClockOffsets {

    private final LongPredicate known;
    private final Map<Long, Long> offsets = new ConcurrentHashMap<>();

    /** @param known whether the producer list has the producer */
    public ClockOffsets(LongPredicate known) {
        this.known = known;
    }

    /** For the producers of this list. */
    public ClockOffsets(Producers producers) {
        this(id -> producers.getProducer(id) != null);
    }

    /**
     * @param generatedAt the alive's timestamp, epoch millis by the producer's clock
     * @param receivedAt when the SDK received it, epoch millis by its own clock
     */
    void alive(long producerId, long generatedAt, long receivedAt) {
        if (known.test(producerId)) {
            offsets.put(producerId, receivedAt - generatedAt);
        }
    }

    /**
     * How old a message of the producer is: from its timestamp to {@code takenAt}, corrected by the
     * producer's offset. Before the producer's first alive its offset is taken as none, so the age is
     * read by the two clocks as they are.
     */
    long age(long producerId, long generatedAt, long takenAt) {
        return takenAt - generatedAt - offsets.getOrDefault(producerId, 0L);
    }
}
