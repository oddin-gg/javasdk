package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Who may write the fields the feed owns - live status, scores, period scores, the match clock - of
 * an entity: the feed, or REST once the feed has gone quiet on it.
 *
 * <p>The watermark is the timestamp of the last live message that wrote those fields, per entity
 * and producer, with when the SDK received it. A message older than the watermark of its producer
 * does not write them; it is still delivered. The feed's and the API's clocks are never compared:
 * REST writes them only when the entity has no watermark younger than {@link #STATUS_AGE} by the
 * SDK's own clock, and a message whose corrected age is over the same limit writes nothing either,
 * being a delayed backlog message REST has since taken over from.
 *
 * <p>The record is bounded, and a watermark lives 24 hours - longer than the match status it
 * protects, so an evicted status does not forget how recent the feed was.
 *
 * <p>Safe for concurrent use.
 */
public final class FeedWatermarks<K> {

    /** The match status age: how long the feed owns an entity after its last live message. */
    public static final Duration STATUS_AGE = Duration.ofMinutes(20);

    static final Duration AGE = Duration.ofHours(24);

    private final Ticker ticker;
    private final Cache<K, Marks> marks;

    public FeedWatermarks(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    FeedWatermarks(long maximumSize, Ticker ticker) {
        this.ticker = ticker;
        this.marks = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new AgedFromLastChange<K, Marks>(AGE, Marks::changedAt))
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    /**
     * Whether a live message may write the feed-owned fields of {@code entity}, recording its
     * watermark when it may: not when it is older than the last one from its producer, nor when its
     * corrected age is over {@link #STATUS_AGE}.
     *
     * @param timestamp the message's timestamp, the feed's clock
     * @param receivedAt when the SDK received it, the SDK's clock
     */
    public boolean feedMayWrite(K entity, long producer, long timestamp, Duration correctedAge, Instant receivedAt) {
        if (correctedAge.compareTo(STATUS_AGE) > 0) {
            return false;
        }
        var admitted = new boolean[1];
        marks.asMap().compute(entity, (k, current) -> {
            Mark last = current == null ? null : current.byProducer().get(producer);
            if (last != null && last.timestamp() > timestamp) {
                return current;
            }
            admitted[0] = true;
            var next = current == null ? new HashMap<Long, Mark>() : new HashMap<>(current.byProducer());
            next.put(producer, new Mark(timestamp, receivedAt));
            return new Marks(Map.copyOf(next), ticker.read());
        });
        return admitted[0];
    }

    /**
     * Runs a REST response's write of the feed-owned fields of {@code entity} if no producer has
     * written them within {@link #STATUS_AGE} of {@code now}, by the SDK's clock. The write runs under
     * the entity's watermark, so a live message admitted meanwhile either comes first, and the write
     * does not happen, or comes after and overwrites it: a REST answer from before the feed resumed
     * cannot replace what the feed wrote.
     *
     * @return whether it wrote
     */
    public boolean restWriteIfQuiet(K entity, Instant now, Runnable write) {
        Instant quietSince = now.minus(STATUS_AGE);
        var wrote = new boolean[1];
        marks.asMap().compute(entity, (k, current) -> {
            boolean quiet = current == null
                    || current.byProducer().values().stream()
                            .allMatch(mark -> mark.receivedAt().isBefore(quietSince));
            if (quiet) {
                write.run();
                wrote[0] = true;
            }
            return current;
        });
        return wrote[0];
    }

    /** How many entities it holds watermarks for. */
    public long size() {
        marks.cleanUp();
        return marks.estimatedSize();
    }

    /** A producer's last live message on an entity: its timestamp, and when the SDK received it. */
    private record Mark(long timestamp, Instant receivedAt) {}

    /** An entity's watermarks, and when one of them last changed, on the ticker. */
    private record Marks(Map<Long, Mark> byProducer, long changedAt) {}
}
