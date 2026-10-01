package com.oddin.oddsfeedsdk.internal.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The fields the feed owns - live status, scores, period scores, the match clock - of each entity,
 * kept with the watermarks that say who may write them: the feed, or REST once the feed has gone
 * quiet on the entity. They are not in an {@link EntityCache}: there one endpoint owns each field,
 * and these have two writers whose order only the watermarks know. A value and its watermark change
 * in one step, so neither writer can put an older value over a newer one, and nothing else is
 * waited for: a write here never waits for an entity cache, or its clear.
 *
 * <p>The watermark is the timestamp of the last live message that wrote, per entity and producer,
 * with when the SDK received it. A message older than the watermark of its producer does not write;
 * it is still delivered. The feed's and the API's clocks are never compared: REST writes only when
 * the entity has no watermark younger than {@link #STATUS_AGE} by the SDK's own clock, and a message
 * whose corrected age is over the same limit writes nothing either, being a delayed backlog message
 * REST has since taken over from.
 *
 * <p>One lock guards it all; what it guards is a map lookup and a few small copies. A record lives 24
 * hours after its last write. Those the feed owns - wrote within the status age - are kept apart
 * from the others, in the order the feed wrote them, and join the others, as their newest, when
 * that ends. Over {@code maximumSize}, the oldest of the others goes - by its last REST write, or by
 * when the feed stopped owning it - and never the one just written: its reader loads
 * the summary again. One the feed owns stays, so REST does not take over a live entity for want of
 * room; past twice the bound, with no other left to go, the oldest the feed owns go, and are
 * counted.
 *
 * <p>Safe for concurrent use.
 */
public final class LiveState<K> {

    /** The match status age: how long the feed owns an entity after its last live message. */
    public static final Duration STATUS_AGE = Duration.ofMinutes(20);

    static final Duration AGE = Duration.ofHours(24);

    private final Ticker ticker;
    private final long maximumSize;
    private final ReentrantLock lock = new ReentrantLock();
    /** Those the feed owns, in the order it wrote them, oldest first; under the lock. */
    private final LinkedHashMap<K, Live> owned = new LinkedHashMap<>();
    /**
     * The others, in the order of their last write or of when the feed stopped owning them, oldest
     * first; under the lock.
     */
    private final LinkedHashMap<K, Live> others = new LinkedHashMap<>();

    private final AtomicLong dropped = new AtomicLong();

    public LiveState(long maximumSize) {
        this(maximumSize, Ticker.systemTicker());
    }

    LiveState(long maximumSize, Ticker ticker) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("a live state holds one record at least");
        }
        this.ticker = ticker;
        this.maximumSize = maximumSize;
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
        lock.lock();
        try {
            long now = ticker.read();
            Live current = current(entity, now);
            Mark last = current == null ? null : current.marks().get(producer);
            if (last != null && last.timestamp() > timestamp) {
                return false;
            }
            var marks = current == null ? new HashMap<Long, Mark>() : new HashMap<>(current.marks());
            marks.put(producer, new Mark(timestamp, receivedAt));
            put(
                    entity,
                    new Live(
                            Map.copyOf(marks),
                            write.applyTo(valuesOf(current)),
                            now,
                            now,
                            current == null ? null : current.restAt()),
                    true,
                    now);
            return true;
        } finally {
            lock.unlock();
        }
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
        lock.lock();
        try {
            long ticks = ticker.read();
            Live current = current(entity, ticks);
            boolean quiet = current == null
                    || current.marks().values().stream()
                            .allMatch(mark -> mark.receivedAt().isBefore(quietSince));
            if (!quiet || abandoned.getAsBoolean()) {
                return false;
            }
            put(
                    entity,
                    new Live(
                            current == null ? Map.of() : current.marks(),
                            write.applyTo(valuesOf(current)),
                            ticks,
                            current == null ? 0 : current.feedAt(),
                            now),
                    false,
                    ticks);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The same, for a write no loader can abandon; for a test. */
    boolean restWriteIfQuiet(K entity, Instant now, LiveWrite write) {
        return restWriteIfQuiet(entity, now, write, () -> false);
    }

    /** The entity's live values, or null when it has no record: then its summary is loaded again. */
    public @Nullable LiveValues get(K entity) {
        Live live;
        lock.lock();
        try {
            live = current(entity, ticker.read());
        } finally {
            lock.unlock();
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

    /** Records the feed owned that did not fit even past the bound, and were dropped. */
    public long dropped() {
        return dropped.get();
    }

    /** How many entities it holds a record for. */
    public long size() {
        lock.lock();
        try {
            return (long) owned.size() + others.size();
        } finally {
            lock.unlock();
        }
    }

    /** Whether it holds the entity; for a test. */
    boolean holds(K entity) {
        return get(entity) != null;
    }

    /** The entity's record, unless it aged out; under the lock. */
    private @Nullable Live current(K entity, long now) {
        Live live = owned.get(entity);
        if (live == null) {
            live = others.get(entity);
        }
        return live == null || aged(live, now) ? null : live;
    }

    /** Writes the record as the newest, with those the feed owns or the others, and keeps the bound. */
    private void put(K entity, Live live, boolean byFeed, long now) {
        owned.remove(entity);
        others.remove(entity);
        (byFeed ? owned : others).put(entity, live);
        trim(entity, now);
    }

    /**
     * Moves those the feed stopped owning to the others, drops what aged out, then, over the bound,
     * the oldest of the others but {@code written}; past twice the bound, with none of those left, the
     * oldest the feed owns, counted. Under the lock.
     */
    private void trim(K written, long now) {
        // the feed wrote these in this order, so those it no longer owns are the oldest
        for (var oldest = owned.entrySet().iterator(); oldest.hasNext(); ) {
            var record = oldest.next();
            if (owns(record.getValue(), now)) {
                break;
            }
            oldest.remove();
            others.put(record.getKey(), record.getValue());
        }
        dropAged(owned, now);
        dropAged(others, now);
        while (owned.size() + others.size() > maximumSize) {
            if (dropOldestBut(others, written)) {
                continue;
            }
            if (owned.size() + others.size() <= 2 * maximumSize || !dropOldestBut(owned, written)) {
                return;
            }
            dropped.incrementAndGet();
        }
    }

    private static <K> void dropAged(Map<K, Live> records, long now) {
        for (Iterator<Live> oldest = records.values().iterator(); oldest.hasNext(); ) {
            if (!aged(oldest.next(), now)) {
                return;
            }
            oldest.remove();
        }
    }

    /** Drops the oldest record but {@code kept} of an insertion-ordered map; false when there is none. */
    private static <K> boolean dropOldestBut(Map<K, Live> records, K kept) {
        Iterator<K> oldest = records.keySet().iterator();
        for (int looked = 0; looked < 2 && oldest.hasNext(); looked++) {
            if (!oldest.next().equals(kept)) {
                oldest.remove();
                return true;
            }
        }
        return false;
    }

    /** Whether the feed wrote the entity within the status age: whether it owns it. */
    private static boolean owns(Live live, long now) {
        return !live.marks().isEmpty() && now - live.feedAt() <= STATUS_AGE.toNanos();
    }

    private static boolean aged(Live live, long now) {
        return now - live.changedAt() > AGE.toNanos();
    }

    private static Map<Field<?>, Object> valuesOf(@Nullable Live live) {
        return live == null ? Map.of() : live.values();
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
