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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The fields the feed owns - live status, scores, period scores, the match clock - of each entity,
 * kept with the watermarks that say who may write them: the feed, or REST once the feed has gone
 * quiet on the entity. They are not in an {@link EntityCache}: there one endpoint owns each field,
 * and these have two writers whose order only the watermarks know. A value and its watermark change
 * in one step, under the entity's record, so neither writer can put an older value over a newer one,
 * and nothing else is waited for: a write here never waits for an entity cache, or its clear.
 *
 * <p>The watermark is the timestamp of the last live message that wrote, per entity and producer,
 * with when the SDK received it. A message older than the watermark of its producer does not write;
 * it is still delivered. The feed's and the API's clocks are never compared: REST writes only when
 * the entity has no watermark younger than {@link #STATUS_AGE} by the SDK's own clock, and a message
 * whose corrected age is over the same limit writes nothing either, being a delayed backlog message
 * REST has since taken over from.
 *
 * <p>The record is bounded, and lives 24 hours after its last write. One the bound evicts while the
 * feed owns its entity - wrote within {@link #STATUS_AGE} - is kept aside, read from there, and
 * taken back by the entity's next write, so REST does not take over a live entity for want of room. What is
 * kept aside is bounded too: once it is as large as the record, it is pruned of what the feed no
 * longer owns - not again before more has been kept aside, when that freed nothing - and what still
 * does not fit is dropped and counted. A record evicted that the feed does not own is gone; whoever
 * reads the entity loads its summary again.
 *
 * <p>Safe for concurrent use.
 */
public final class LiveState<K> {

    /** The match status age: how long the feed owns an entity after its last live message. */
    public static final Duration STATUS_AGE = Duration.ofMinutes(20);

    static final Duration AGE = Duration.ofHours(24);

    private final Ticker ticker;
    private final long maximumSize;
    private final Cache<K, Live> records;
    /** Records the bound evicted while the feed owned their entity. */
    private final ConcurrentHashMap<K, Live> spilled = new ConcurrentHashMap<>();
    /** How many records may be kept aside between two prunes that freed nothing. */
    private final long pruneEvery;

    private final ReentrantLock pruning = new ReentrantLock();
    private final AtomicLong spills = new AtomicLong();
    private final AtomicLong prunes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** Changed under {@link #pruning}: whether the last prune freed anything, and when it ran. */
    private volatile boolean lastPruneFreed = true;

    private volatile long spillsAtLastPrune;

    public LiveState(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    LiveState(long maximumSize, Ticker ticker) {
        this.ticker = ticker;
        this.maximumSize = maximumSize;
        this.pruneEvery = Math.max(1, maximumSize / 8);
        this.records = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new AgedFromLastChange<K, Live>(AGE, Live::changedAt))
                .ticker(ticker)
                .executor(Runnable::run)
                .<K, Live>evictionListener((entity, evicted, cause) -> {
                    if (cause == RemovalCause.SIZE && entity != null && evicted != null && owns(evicted)) {
                        spill(entity, evicted);
                    }
                })
                .build();
    }

    /**
     * Writes a live message's values for {@code entity}, and records its watermark, unless it is
     * older than the last one from its producer or its corrected age is over {@link #STATUS_AGE}.
     *
     * @param timestamp the message's timestamp, the feed's clock
     * @param receivedAt when the SDK received it, the SDK's clock
     * @return whether it wrote
     */
    public boolean feedWriteIfNewer(
            K entity, long producer, long timestamp, Duration correctedAge, Instant receivedAt, LiveWrite write) {
        if (correctedAge.compareTo(STATUS_AGE) > 0) {
            return false;
        }
        var admitted = new boolean[1];
        var taken = new Live[1];
        records.asMap().compute(entity, (k, present) -> {
            Live current = takeBack(k, present, taken);
            Mark last = current == null ? null : current.marks().get(producer);
            if (last != null && last.timestamp() > timestamp) {
                return current;
            }
            admitted[0] = true;
            var marks = current == null ? new HashMap<Long, Mark>() : new HashMap<>(current.marks());
            marks.put(producer, new Mark(timestamp, receivedAt));
            long now = ticker.read();
            return new Live(
                    Map.copyOf(marks),
                    write.applyTo(valuesOf(current)),
                    now,
                    now,
                    current == null ? null : current.restAt());
        });
        release(entity, taken[0]);
        return admitted[0];
    }

    /**
     * Writes a REST response's values for {@code entity} if no producer has written within {@link
     * #STATUS_AGE} of {@code now}, by the SDK's clock. A live message admitted meanwhile either comes
     * first, and this does not write, or comes after and overwrites it: a REST answer from before the
     * feed resumed cannot replace what the feed wrote. Nor can a fetch its loader abandoned replace
     * what a newer one wrote: {@code abandoned} is asked in the same step as the write.
     *
     * @param abandoned whether the fetch the values came from was abandoned
     * @return whether it wrote
     */
    public boolean restWriteIfQuiet(K entity, Instant now, LiveWrite write, BooleanSupplier abandoned) {
        Instant quietSince = now.minus(STATUS_AGE);
        var wrote = new boolean[1];
        var taken = new Live[1];
        records.asMap().compute(entity, (k, present) -> {
            // one evicted while the feed owned the entity comes back, and is the feed's still
            Live current = takeBack(k, present, taken);
            boolean quiet = current == null
                    || current.marks().values().stream()
                            .allMatch(mark -> mark.receivedAt().isBefore(quietSince));
            if (!quiet || abandoned.getAsBoolean()) {
                return current;
            }
            wrote[0] = true;
            return new Live(
                    current == null ? Map.of() : current.marks(),
                    write.applyTo(valuesOf(current)),
                    ticker.read(),
                    current == null ? 0 : current.feedAt(),
                    now);
        });
        release(entity, taken[0]);
        return wrote[0];
    }

    /** The same, for a write no loader can abandon; for a test. */
    boolean restWriteIfQuiet(K entity, Instant now, LiveWrite write) {
        return restWriteIfQuiet(entity, now, write, () -> false);
    }

    /** The entity's live values, or null when it has no record: then its summary is loaded again. */
    public @Nullable LiveValues get(K entity) {
        Live live = records.getIfPresent(entity);
        if (live == null) {
            live = kept(entity);
        }
        if (live == null) {
            // a write took it back, or the bound kept it aside, between the two looks
            live = records.getIfPresent(entity);
        }
        if (live == null) {
            return null;
        }
        Instant lastFeed = live.marks().values().stream()
                .map(Mark::receivedAt)
                .max(Instant::compareTo)
                .orElse(null);
        return new LiveValues(live.values(), lastFeed, live.restAt());
    }

    /** Records kept aside for the feed that did not fit, and were dropped. */
    public long dropped() {
        return dropped.get();
    }

    /**
     * The entity's record: the one held, or the one kept aside when the bound evicted it, which
     * {@code taken} is told of, to be released once the compute has put its result in place.
     */
    private @Nullable Live takeBack(K entity, @Nullable Live present, @Nullable Live[] taken) {
        if (present != null) {
            return present;
        }
        Live live = spilled.get(entity);
        taken[0] = live;
        return live == null || aged(live) ? null : live;
    }

    private @Nullable Live kept(K entity) {
        Live live = spilled.get(entity);
        return live == null || aged(live) ? null : live;
    }

    /** Aged out while kept aside, as it would have in the record. */
    private boolean aged(Live live) {
        return ticker.read() - live.changedAt() > AGE.toNanos();
    }

    /**
     * Lets go of what a compute took back from the spill, once its result is in the record: until
     * then a read still finds it kept aside, and a write that threw leaves it there. Only while the
     * record holds the entity: the bound can evict it again within the same call, and keep the very
     * same record aside once more. A newer one kept aside since is left alone.
     */
    private void release(K entity, @Nullable Live taken) {
        if (taken != null) {
            spilled.computeIfPresent(
                    entity,
                    (k, kept) -> kept.equals(taken) && records.policy().getIfPresentQuietly(k) != null ? null : kept);
        }
    }

    private void spill(K entity, Live evicted) {
        long spilledSoFar = spills.incrementAndGet();
        if (spilled.size() >= maximumSize && !spilled.containsKey(entity)) {
            prune(spilledSoFar);
            if (spilled.size() >= maximumSize) {
                dropped.incrementAndGet();
                return;
            }
        }
        // what the record evicts is never older than what was kept aside for the same entity
        spilled.merge(entity, evicted, (kept, newer) -> newer.changedAt() - kept.changedAt() >= 0 ? newer : kept);
    }

    /**
     * Drops what the feed no longer owns from what is kept aside, unless the last prune freed nothing
     * and too little has been kept aside since: in a burst of live entities over the bound every
     * kept record is owned, and scanning them again for each would free nothing each time.
     */
    private void prune(long spilledSoFar) {
        if ((!lastPruneFreed && spilledSoFar - spillsAtLastPrune < pruneEvery) || !pruning.tryLock()) {
            return;
        }
        try {
            prunes.incrementAndGet();
            spillsAtLastPrune = spilledSoFar;
            lastPruneFreed = spilled.values().removeIf(kept -> !owns(kept));
        } finally {
            pruning.unlock();
        }
    }

    /** Whether the feed wrote the entity within the status age: whether it owns it. */
    private boolean owns(Live live) {
        return !live.marks().isEmpty() && ticker.read() - live.feedAt() <= STATUS_AGE.toNanos();
    }

    private static Map<Field<?>, Object> valuesOf(@Nullable Live live) {
        return live == null ? Map.of() : live.values();
    }

    /** Whether the bounded record holds the entity, without counting as a use of it; for a test. */
    boolean holds(K entity) {
        records.cleanUp();
        return records.policy().getIfPresentQuietly(entity) != null;
    }

    /** How many records are kept aside; for a test. */
    int keptAside() {
        return spilled.size();
    }

    /** How many times what is kept aside was scanned; for a test. */
    long prunes() {
        return prunes.get();
    }

    /** How many entities it holds a record for, those kept aside not counted. */
    public long size() {
        records.cleanUp();
        return records.estimatedSize();
    }

    /** An entity's live values at one moment, all from the same write, and how recent they are. */
    public static final class LiveValues {
        private final Map<Field<?>, Object> values;
        private final @Nullable Instant lastFeed;
        private final @Nullable Instant lastRest;

        LiveValues(Map<Field<?>, Object> values, @Nullable Instant lastFeed, @Nullable Instant lastRest) {
            this.values = values;
            this.lastFeed = lastFeed;
            this.lastRest = lastRest;
        }

        @SuppressWarnings("unchecked") // a field holds only what a LiveWrite put for it
        public <T> @Nullable T get(Field<T> field) {
            return (T) values.get(field);
        }

        /**
         * Whether the feed or REST wrote them within {@link #STATUS_AGE} of {@code now}, by the SDK's
         * clock: if not, the match summary is loaded again - a match the feed is quiet on is as old
         * as REST's last word on it.
         */
        public boolean isFresh(Instant now) {
            Instant since = now.minus(STATUS_AGE);
            return (lastFeed != null && !lastFeed.isBefore(since)) || (lastRest != null && !lastRest.isBefore(since));
        }
    }

    /** A producer's last live message on an entity: its timestamp, and when the SDK received it. */
    private record Mark(long timestamp, Instant receivedAt) {}

    /**
     * An entity's watermarks and values.
     *
     * @param changedAt when it was last written, on the ticker: what its age counts from
     * @param feedAt when the feed last wrote it, on the ticker; meaningless while it has no marks
     * @param restAt when REST last wrote it, by the SDK's clock; null for never
     */
    private record Live(
            Map<Long, Mark> marks,
            Map<Field<?>, Object> values,
            long changedAt,
            long feedAt,
            @Nullable Instant restAt) {}
}
