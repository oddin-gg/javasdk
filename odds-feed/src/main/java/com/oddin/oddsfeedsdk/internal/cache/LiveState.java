package com.oddin.oddsfeedsdk.internal.cache;

import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.internal.clock.FeedClock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The fields the feed owns - live status, scores, period scores, the match clock, the winner - of
 * each entity, kept with the watermarks that say who may write them: the feed, or REST once the feed
 * has gone quiet on the entity; while it is live, REST only fills what the feed has not sent. They
 * are not in an {@link EntityCache}: there one endpoint owns each field, and these have two writers
 * whose order only the watermarks know. A value and its watermark change in one step, so neither
 * writer can put an older value over a newer one, and nothing else is waited for: a write here
 * never waits for an entity cache, or its clear.
 *
 * <p>The watermark is the timestamp of the last live message that wrote, per entity and producer,
 * with when the SDK received it. A message older than the watermark of its producer does not write;
 * it is still delivered. One exception: a watermark that no delivery delay explains must not hold the
 * correct messages that follow back, so a message older than it writes, and takes the watermark down
 * to its own timestamp, when it was received after every message that wrote, was generated so much
 * before the watermark's that the two deliveries' delays cannot account for the gap (see {@link Takeover}), is itself timely,
 * and is not older than the timestamp the watermark jumped from. A replayed message writes in the order it comes. The feed's and the API's
 * clocks are never compared: REST writes only when the entity has no watermark younger than {@link
 * #STATUS_AGE} by the SDK's own clock, and a message whose corrected age is over the same limit
 * writes nothing either, being a delayed backlog message REST has since taken over from. That
 * clock is the feed's monotonic one, read in millis: how long ago the SDK received a message, or
 * wrote a response, is a duration between two local events, so a step of the wall clock changes
 * none of them. The message's own timestamp is the producer's and is only compared with the
 * producer's.
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

    /** A reading that was never taken: the feed's monotonic clock reads 1 or more. */
    private static final long NEVER = 0;

    /** A mark with no stamp before a jump to keep: no takeover is bound below any. */
    private static final long NO_FLOOR = Long.MIN_VALUE;

    private final FeedClock clock;
    private final Takeover takeover;
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
        this(maximumSize, FeedClock.system());
    }

    /** With the feed's clock, of which the monotonic side counts every age here. */
    public LiveState(long maximumSize, FeedClock clock) {
        this(maximumSize, clock, Takeover.DEFAULTS);
    }

    /** With the margins of the exception to the watermark's order. */
    public LiveState(long maximumSize, FeedClock clock, Takeover takeover) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("a live state holds one record at least");
        }
        this.clock = clock;
        this.takeover = takeover;
        this.maximumSize = maximumSize;
    }

    /**
     * Writes a live message's values for {@code entity}, and records its watermark, unless it is
     * older than the last one from its producer, with the exception of {@link Takeover}, or its
     * corrected age is over {@link #STATUS_AGE}. For a message that may take the watermark over.
     *
     * @param timestamp the message's timestamp, the feed's clock
     * @param receivedAt when the SDK received it, in millis on the feed's monotonic clock
     * @return whether it wrote
     */
    public boolean feedWriteIfNewer(
            K entity, long producer, long timestamp, Duration correctedAge, long receivedAt, LiveWrite write) {
        return feedWriteIfNewer(entity, producer, timestamp, correctedAge, receivedAt, true, write);
    }

    /**
     * As above, for a message that {@code mayTakeOver} the watermark of a later timestamp, or not:
     * then it is refused when older than the watermark, whatever else is true of it.
     */
    public boolean feedWriteIfNewer(
            K entity,
            long producer,
            long timestamp,
            Duration correctedAge,
            long receivedAt,
            boolean mayTakeOver,
            LiveWrite write) {
        if (correctedAge.compareTo(STATUS_AGE) > 0) {
            return false;
        }
        return feedWrite(entity, producer, timestamp, receivedAt, mayTakeOver ? correctedAge : null, write, true);
    }

    /**
     * Writes a replayed message's values for {@code entity} whatever its timestamp and its age: a
     * replay's messages are old by design, and a run played again repeats the timestamps of the one
     * before, so they write in the order they come, as 0.0.x wrote every message. The watermark
     * keeps the newest timestamp that wrote.
     *
     * @param timestamp the message's timestamp, the feed's clock
     * @param receivedAt when the SDK received it, in millis on the feed's monotonic clock
     */
    public void feedWriteReplayed(K entity, long producer, long timestamp, long receivedAt, LiveWrite write) {
        feedWrite(entity, producer, timestamp, receivedAt, null, write, false);
    }

    /**
     * A feed write, refused when {@code inOrder} and older than its producer's watermark, unless
     * {@code correctedAge} is given and {@link Takeover#allows} the message to replace the watermark.
     */
    private boolean feedWrite(
            K entity,
            long producer,
            long timestamp,
            long receivedAt,
            @Nullable Duration correctedAge,
            LiveWrite write,
            boolean inOrder) {
        lock.lock();
        try {
            long now = clock.monoMillis();
            Live current = current(entity, now);
            Mark last = current == null ? null : current.marks().get(producer);
            boolean older = false;
            long floor = last == null ? NO_FLOOR : last.floor();
            if (inOrder && last != null && last.timestamp() > timestamp) {
                if (correctedAge == null
                        || !takeover.allows(
                                last.timestamp(),
                                last.receivedAt(),
                                last.newestReceivedAt(),
                                floor,
                                timestamp,
                                receivedAt,
                                correctedAge)) {
                    return false;
                }
                older = true;
                // the message is believed over the watermark: the next jump is judged from it
                floor = NO_FLOOR;
            } else if (inOrder && last != null) {
                if (correctedAge != null && takeover.atProducersNow(correctedAge)) {
                    // the watermark is the producer's now: a stamp from before it is stale, and no
                    // takeover can pass it
                    floor = NO_FLOOR;
                } else if (floor == NO_FLOOR
                        && takeover.jumped(last.timestamp(), last.receivedAt(), timestamp, receivedAt)) {
                    // the stamp before the jump: what an older message that is yet to come must not go below
                    floor = last.timestamp();
                }
            }
            var marks = current == null ? new HashMap<Long, Mark>() : new HashMap<>(current.marks());
            // a message that took the watermark over lowers it: the next one is judged by this one
            long newest = last == null || older ? timestamp : Math.max(last.timestamp(), timestamp);
            // never moved back: a write received earlier than one before it, by a session that took
            // the lock later, leaves the latest receipt where it was
            long latest = last == null ? receivedAt : Math.max(last.newestReceivedAt(), receivedAt);
            marks.put(producer, new Mark(newest, receivedAt, latest, floor));
            put(
                    entity,
                    new Live(
                            Map.copyOf(marks),
                            write.applyTo(valuesOf(current)),
                            now,
                            now,
                            current == null ? NEVER : current.restAt()),
                    true,
                    now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes a REST response's values for {@code entity} if no producer has written within {@link
     * #STATUS_AGE} of now, by the SDK's clock. A live message admitted meanwhile either comes
     * first, and this does not write, or comes after and overwrites it: a REST answer from before the
     * feed resumed cannot replace what the feed wrote. Nor can a fetch its loader abandoned replace
     * what a newer one wrote: {@code abandoned} is asked in the same step as the write.
     *
     * @param abandoned whether the fetch the values came from was abandoned
     * @return whether it wrote
     */
    public boolean restWriteIfQuiet(K entity, LiveWrite write, BooleanSupplier abandoned) {
        lock.lock();
        try {
            long now = clock.monoMillis();
            long quietSince = now - STATUS_AGE.toMillis();
            Live current = current(entity, now);
            boolean quiet = current == null
                    || current.marks().values().stream().allMatch(mark -> mark.newestReceivedAt() < quietSince);
            if (!quiet || abandoned.getAsBoolean()) {
                return false;
            }
            put(
                    entity,
                    new Live(
                            current == null ? Map.of() : current.marks(),
                            write.applyTo(valuesOf(current)),
                            now,
                            current == null ? 0 : current.feedAt(),
                            now),
                    false,
                    now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The same, for a write no loader can abandon; for a test. */
    boolean restWriteIfQuiet(K entity, LiveWrite write) {
        return restWriteIfQuiet(entity, write, () -> false);
    }

    /**
     * Fills the fields the entity's record has no value for from a REST response, whoever owns it:
     * for a value a live feed may never send, such as a match's winner, which REST may have first.
     * It clears nothing, and changes neither the watermarks nor how fresh the values are, so it
     * takes nothing over from the feed. An entity with no record gets none: its next read loads the
     * summary anyway. Nor does a fetch its loader abandoned fill anything.
     *
     * @param abandoned whether the fetch the values came from was abandoned
     * @return whether it filled anything
     */
    public boolean restFill(K entity, LiveWrite write, BooleanSupplier abandoned) {
        lock.lock();
        try {
            Live current = current(entity, clock.monoMillis());
            if (current == null || abandoned.getAsBoolean()) {
                return false;
            }
            var values = write.fill(current.values());
            if (values.equals(current.values())) {
                return false;
            }
            // in its place: a fill is no write, and moves nothing nearer to or further from eviction
            (owned.containsKey(entity) ? owned : others)
                    .put(
                            entity,
                            new Live(current.marks(), values, current.changedAt(), current.feedAt(), current.restAt()));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The entity's live values, or null when it has no record: then its summary is loaded again. */
    public @Nullable LiveValues get(K entity) {
        Live live;
        lock.lock();
        try {
            live = current(entity, clock.monoMillis());
        } finally {
            lock.unlock();
        }
        if (live == null) {
            return null;
        }
        long lastFeed = live.marks().values().stream()
                .mapToLong(Mark::newestReceivedAt)
                .max()
                .orElse(NEVER);
        return new LiveValues(live.values(), lastFeed, live.restAt());
    }

    /** Records the feed owned that did not fit even past the bound, and were dropped. */
    public long dropped() {
        return dropped.get();
    }

    /** How many records it holds before the oldest of those the feed does not own go. */
    public long maximumSize() {
        return maximumSize;
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
        return !live.marks().isEmpty() && now - live.feedAt() <= STATUS_AGE.toMillis();
    }

    private static boolean aged(Live live, long now) {
        return now - live.changedAt() > AGE.toMillis();
    }

    private static Map<Field<?>, Object> valuesOf(@Nullable Live live) {
        return live == null ? Map.of() : live.values();
    }

    /** An entity's live values at one moment, from one write and the fills since, and how recent they are. */
    public static final class LiveValues {
        private final Map<Field<?>, Object> values;
        private final long lastFeed;
        private final long lastRest;

        LiveValues(Map<Field<?>, Object> values, long lastFeed, long lastRest) {
            this.values = values;
            this.lastFeed = lastFeed;
            this.lastRest = lastRest;
        }

        @SuppressWarnings("unchecked") // a field holds only what a LiveWrite put for it
        public <T> @Nullable T get(Field<T> field) {
            return (T) values.get(field);
        }

        /**
         * Whether the feed or REST wrote them within {@link #STATUS_AGE} of {@code now}, in millis on
         * the feed's monotonic clock: if not, the match summary is loaded again - a match the feed is
         * quiet on is as old as REST's last word on it.
         */
        public boolean isFresh(long now) {
            long since = now - STATUS_AGE.toMillis();
            return (lastFeed != NEVER && lastFeed >= since) || (lastRest != NEVER && lastRest >= since);
        }
    }

    /**
     * The exception to the order of the watermark: a message older than its producer's watermark is
     * believed over it when the watermark's timestamp is more likely a bad one than the message a
     * late one.
     *
     * <p>A message received {@code R} after the watermark's message, yet generated {@code G} before
     * it, had a delivery {@code R + G} slower than the other's, or the watermark's timestamp is ahead
     * of the true time. It replaces the watermark, and takes it down to its own timestamp, when all
     * of these hold; the producer's clock is never compared with the SDK's but in the corrected age
     * the caller computes:
     *
     * <ol>
     *   <li>it was received strictly after every message that wrote for the producer - not only
     *       the watermark's, which a message with a newer stamp received earlier may have written
     *       last - by the monotonic clock: two sessions that handle messages in another order than
     *       they received them leave the order of receipt right;
     *   <li>{@code R + G} is more than the {@code margin}: two deliveries that differ by more than
     *       that are no jitter;
     *   <li>its corrected age is at most {@code timelyAge}: it is a timely message, not one that
     *       sat in a backlog, which would look the same by {@code R + G}. The bound on a wrong
     *       takeover is then an alive later than {@code margin - timelyAge}, which is why the
     *       timely age is at most the margin;
     *   <li>its timestamp is after the epoch: it is the producer's, and nothing else of it is
     *       trusted; the age of one far before it wraps around;
     *   <li>its timestamp is not below the one the watermark had before it jumped: a write that
     *       raises the watermark by more than the margin more than the time between the two receipts
     *       leaves the stamp it raised it from behind, and a message older than that one is older
     *       than a write that was correct, whichever session took the lock first. That stamp is kept
     *       through further raises, which are stamps ahead of the producer's now, and forgotten when a
     *       message takes the watermark over, since the next jump is then judged from that message,
     *       or when a message at the producer's now (see {@link #atProducersNow}) raises it: the
     *       watermark is then no further ahead than a timely message, and the stamp left behind is
     *       as stale as the quiet since it was written - kept, it would let a later jump judge by
     *       it instead of by the correct write that came between.
     * </ol>
     *
     * A watermark ahead of the true time by less than the margin holds the messages that follow it
     * back until their timestamps catch up, as before. Of two timestamps ahead, the one received last
     * writes when the same rule holds for it; stamps closer than the margin leave the first in charge
     * until they catch up.
     *
     * @param margin the least {@code R + G} that makes the watermark a bad one
     * @param timelyAge the most a message's corrected age may be to take over
     */
    public record Takeover(Duration margin, Duration timelyAge) {

        /** 30 seconds and 10 seconds. */
        public static final Takeover DEFAULTS = new Takeover(Duration.ofSeconds(30), Duration.ofSeconds(10));

        public Takeover {
            if (!margin.isPositive() || !timelyAge.isPositive() || timelyAge.compareTo(margin) > 0) {
                throw new IllegalArgumentException(
                        "a takeover needs a positive margin and a positive timely age of at most the margin, was "
                                + margin + " and " + timelyAge);
            }
        }

        /** What the configuration sets. */
        public static Takeover from(OddsFeedConfiguration configuration) {
            return new Takeover(configuration.getFutureStampMargin(), configuration.getTimelyMessageAge());
        }

        /**
         * Whether a message that is not older than the watermark raises it by more than the margin
         * more than the time since the watermark's message was received: a stamp ahead of the stream
         * it follows, where a quiet match's next message raises it by as much as the quiet lasted.
         *
         * @param watermark the watermark's timestamp, at most {@code timestamp}
         * @param watermarkReceivedAt when its message was received, on the monotonic clock
         */
        boolean jumped(long watermark, long watermarkReceivedAt, long timestamp, long receivedAt) {
            long raised = timestamp - watermark;
            if (raised < 0) {
                // both are the producer's, any long: the difference of two that overflowed
                raised = Long.MAX_VALUE;
            }
            // a message received before the watermark's, by a session that took the lock later, is
            // no time passed
            long elapsed = Math.max(0, receivedAt - watermarkReceivedAt);
            return raised > margin.toMillis() + elapsed;
        }

        /**
         * Whether a message's stamp is at the producer's now: its corrected age is within the timely
         * age on either side. A stamp ahead of the producer's clock has a negative age, so the age at
         * most {@code timelyAge} is not enough.
         *
         * @param correctedAge the message's age by the producer's clock, less the producer's offset
         */
        boolean atProducersNow(Duration correctedAge) {
            // compared, not negated or taken absolute: the age is any duration
            return correctedAge.compareTo(timelyAge) <= 0 && correctedAge.compareTo(timelyAge.negated()) >= 0;
        }

        /**
         * Whether a message older than the watermark replaces it.
         *
         * @param watermark the watermark's timestamp, greater than {@code timestamp}
         * @param watermarkReceivedAt when its message was received, on the monotonic clock
         * @param newestReceivedAt the latest receipt of any message that wrote for the producer, at
         *     least {@code watermarkReceivedAt}
         * @param floor the stamp the watermark had before it jumped, or none
         * @param timestamp the message's timestamp
         * @param receivedAt when it was received
         * @param correctedAge the message's age by the producer's clock, less the producer's offset
         */
        boolean allows(
                long watermark,
                long watermarkReceivedAt,
                long newestReceivedAt,
                long floor,
                long timestamp,
                long receivedAt,
                Duration correctedAge) {
            // the timestamp is the producer's, any long: nothing below may overflow on it
            if (timestamp <= 0) {
                return false;
            }
            if (receivedAt <= newestReceivedAt) {
                return false;
            }
            if (timestamp < floor) {
                return false;
            }
            if (correctedAge.compareTo(timelyAge) > 0) {
                return false;
            }
            long receivedAfter = receivedAt - watermarkReceivedAt;
            long generatedBefore = watermark - timestamp;
            // R + G > margin, as R > margin - G: with G positive, neither side can overflow
            return receivedAfter > margin.toMillis() - generatedBefore;
        }
    }

    /**
     * A producer's last live message on an entity: its timestamp, the producer's clock, and when the
     * SDK received it, on the feed's monotonic clock; and the latest receipt of any message that wrote
     * for the producer, which only grows; and the stamp the watermark had before it last jumped,
     * {@link #NO_FLOOR} if it did not.
     */
    private record Mark(long timestamp, long receivedAt, long newestReceivedAt, long floor) {}

    /**
     * An entity's watermarks and values.
     *
     * @param changedAt when it was last written, on the feed's monotonic clock: what its age counts from
     * @param feedAt when the feed last wrote it, the same; meaningless while it has no marks
     * @param restAt when REST last wrote it, the same; {@link #NEVER} for never
     */
    private record Live(
            Map<Long, Mark> marks, Map<Field<?>, Object> values, long changedAt, long feedAt, long restAt) {}
}
