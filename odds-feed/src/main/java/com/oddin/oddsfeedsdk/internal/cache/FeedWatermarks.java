package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * protects, so an evicted status does not forget how recent the feed was. A record the bound evicts
 * while it still owns its entity - changed within {@link #STATUS_AGE} - is kept aside and taken
 * back by the entity's next write, so REST does not take over a live entity for want of room. What
 * is kept aside is pruned of records past the status age once it outgrows the bound; it holds no
 * more than the entities the feed wrote within the status age.
 *
 * <p>Safe for concurrent use.
 */
public final class FeedWatermarks<K> {

    /** The match status age: how long the feed owns an entity after its last live message. */
    public static final Duration STATUS_AGE = Duration.ofMinutes(20);

    static final Duration AGE = Duration.ofHours(24);

    private final Ticker ticker;
    private final long maximumSize;
    private final Cache<K, Marks> marks;
    /** Records the bound evicted while they still owned their entity. */
    private final ConcurrentHashMap<K, Marks> spilled = new ConcurrentHashMap<>();

    public FeedWatermarks(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    FeedWatermarks(long maximumSize, Ticker ticker) {
        this.ticker = ticker;
        this.maximumSize = maximumSize;
        this.marks = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new AgedFromLastChange<K, Marks>(AGE, Marks::changedAt))
                .ticker(ticker)
                .executor(Runnable::run)
                .<K, Marks>evictionListener((entity, evicted, cause) -> {
                    if (cause == RemovalCause.SIZE && entity != null && evicted != null && owns(evicted)) {
                        spill(entity, evicted);
                    }
                })
                .build();
    }

    /**
     * Runs a live message's write of the feed-owned fields of {@code entity}, and records its
     * watermark, unless it is older than the last one from its producer or its corrected age is over
     * {@link #STATUS_AGE}. The write runs under the entity's watermark, so two sessions writing the
     * same entity cannot leave the older message's fields behind the newer watermark. It must not
     * touch these watermarks itself.
     *
     * @param timestamp the message's timestamp, the feed's clock
     * @param receivedAt when the SDK received it, the SDK's clock
     * @return whether it wrote
     */
    public boolean feedWriteIfNewer(
            K entity, long producer, long timestamp, Duration correctedAge, Instant receivedAt, Runnable write) {
        if (correctedAge.compareTo(STATUS_AGE) > 0) {
            return false;
        }
        var admitted = new boolean[1];
        marks.asMap().compute(entity, (k, present) -> {
            Marks current = present != null ? present : spilled.remove(k);
            Mark last = current == null ? null : current.byProducer().get(producer);
            if (last != null && last.timestamp() > timestamp) {
                return current;
            }
            write.run();
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
     * cannot replace what the feed wrote. It must not touch these watermarks itself.
     *
     * @return whether it wrote
     */
    public boolean restWriteIfQuiet(K entity, Instant now, Runnable write) {
        Instant quietSince = now.minus(STATUS_AGE);
        var wrote = new boolean[1];
        marks.asMap().compute(entity, (k, present) -> {
            // one evicted while it owned the entity comes back, and owns it still
            Marks current = present != null ? present : spilled.remove(k);
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

    private void spill(K entity, Marks evicted) {
        spilled.merge(entity, evicted, (kept, newer) -> newer.changedAt() - kept.changedAt() > 0 ? newer : kept);
        if (spilled.size() > maximumSize) {
            spilled.values().removeIf(kept -> !owns(kept));
        }
    }

    /** Whether the feed changed these watermarks within the status age: whether they own the entity. */
    private boolean owns(Marks kept) {
        return ticker.read() - kept.changedAt() <= STATUS_AGE.toNanos();
    }

    /** Whether the bounded record holds the entity, without counting as a use of it; for a test. */
    boolean holds(K entity) {
        marks.cleanUp();
        return marks.policy().getIfPresentQuietly(entity) != null;
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
