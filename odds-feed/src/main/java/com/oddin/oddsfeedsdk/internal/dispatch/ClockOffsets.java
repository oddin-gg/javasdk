package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.internal.clock.ClockOffset;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How far each producer's clock is from the SDK's, measured by the alive dispatcher on the alives of
 * the SDK's own alive channel, which no backlog delays: when the SDK received an alive less when the
 * producer stamped it. The session dispatchers read it to tell how old a message is by the
 * producer's own clock - a message older than the match status age writes no live state (KD-14).
 *
 * <p>The recovery actor measures the same on the same alives for its safety net; this copy is the
 * dispatchers', so reading it waits for no actor. Only the producers of the producer list have an
 * offset: an alive of any other is not kept, as the actor does not keep it, so whatever ids the feed
 * sends, the map holds one entry per producer of the list at most.
 *
 * <p>It also tells what a message may move the SDK's marks to - the recovery's checkpoints and
 * safe points - see {@link #mark}, and the producer's now at a message's receipt, which the live
 * state tells a watermark from a bad stamp by - see {@link #producerNow}. Both dispatchers ask it,
 * so the recovery actor takes the same mark from one message that the live state takes the same
 * now for. A mark is clamped by the most cautious offset of the last minute, not the latest one,
 * which an alive stamped ahead has already moved when the message that needs the clamp is handled:
 * the session's copy of that very alive, or a message queued behind a callback while the alive
 * dispatcher went on. Each producer keeps the offsets of its alives over the window, in a small
 * array that is replaced whole, so a reader never waits for the alive dispatcher. Safe for
 * concurrent use.
 */
public final class ClockOffsets {

    private static final Logger LOG = LoggerFactory.getLogger(ClockOffsets.class);

    /** How far ahead of its producer's now a stamp has to be to be logged: below it is jitter. */
    private static final long LOGGED_AHEAD = Duration.ofSeconds(1).toMillis();

    /** How often a producer's stamps ahead are logged, at most. */
    private static final long LOG_EVERY = Duration.ofMinutes(1).toMillis();

    /** How long an offset clamps the marks after it was measured, by the monotonic clock. */
    private static final long WINDOW = Duration.ofMinutes(1).toMillis();

    /**
     * The most offsets a producer keeps: a producer sends an alive every few seconds, so a minute
     * holds a dozen; a faster one gets a shorter window rather than a larger array.
     */
    private static final int KEPT = 64;

    /** The monotonic reading of the "no offset" every producer starts with: before any alive. */
    private static final long BEFORE_ANY_ALIVE = Long.MIN_VALUE;

    /** An offset, with when the alive that showed it was received, on the monotonic clock. */
    private record Measured(ClockOffset offset, long mono) {}

    private final LongPredicate known;
    /**
     * Each producer's offsets, oldest first: those of the window, and the newest one older than it,
     * which a lone alive after a silence is measured against. It starts with "no offset", the SDK's
     * own clock, so the first alive does not vouch for itself either. Replaced whole, never changed.
     */
    private final Map<Long, Measured[]> kept = new ConcurrentHashMap<>();
    /** When a producer's stamp ahead was last logged, on the monotonic clock; one entry per producer of the list. */
    private final Map<Long, Long> loggedAt = new ConcurrentHashMap<>();

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
     * @param received when the SDK received it, by its own clock
     */
    void alive(long producerId, long generatedAt, Stamp received) {
        if (!known.test(producerId)) {
            return;
        }
        var offset = ClockOffset.measured(generatedAt, received);
        var measured = new Measured(offset, received.mono());
        kept.compute(producerId, (id, before) -> {
            if (before == null) {
                // before the first alive the offset is none: the SDK's own clock, stepping with it
                var none = new Measured(new ClockOffset(0, offset.skew()), BEFORE_ANY_ALIVE);
                return new Measured[] {none, measured};
            }
            var all = Arrays.copyOf(before, before.length + 1);
            all[before.length] = measured;
            // the newest older than the window stays, as the one before a lone alive
            int first = 0;
            while (first + 1 < all.length && all[first + 1].mono() < received.mono() - WINDOW) {
                first++;
            }
            first = Math.max(first, all.length - KEPT);
            return first == 0 ? all : Arrays.copyOfRange(all, first, all.length);
        });
    }

    /**
     * How old a message of the producer is: from its timestamp to {@code takenAt}, corrected by the
     * producer's latest offset. Before the producer's first alive its offset is taken as none, so the
     * age is read by the two clocks as they are.
     */
    long age(long producerId, long generatedAt, long takenAt) {
        var offsets = kept.get(producerId);
        return takenAt
                - generatedAt
                - (offsets == null ? 0 : offsets[offsets.length - 1].offset().offset());
    }

    /**
     * The stamp the SDK takes a mark from, for a message of the producer: its timestamp, but no later
     * than the producer's now at receipt - the SDK's wall clock then, less the producer's offset, as of
     * the reading. The offset is the most cautious of the last minute's, the one that puts the
     * producer's now earliest, and always one from before the newest alive: so an alive stamped
     * ahead does not vouch for its own stamp, nor for that of a message received before it and
     * handled after. A producer whose clock goes back is taken at once, and its marks go down; one
     * whose clock goes forward, or is really ahead, is taken a minute later: every error lowers a
     * mark, which only makes a recovery larger. A component with a fast clock, or a bad value,
     * cannot move a checkpoint or a watermark past what the producer has sent: that would leave a
     * gap's start too late to ask for what lies before it, or hold later messages with right
     * stamps back as older. Before the producer's first alive its offset is taken as none. Every
     * message is clamped; a stamp more than a second ahead is also logged, once a minute per
     * producer. What the client sees of the message stays as it is.
     *
     * @param generatedAt the message's timestamp, epoch millis by the producer's clock
     * @param received when the SDK received it, both readings of its clock
     */
    long mark(long producerId, long generatedAt, Stamp received) {
        long offset = cautious(producerId, received);
        long now = received.wall() - offset;
        long ahead = generatedAt - now;
        if (ahead <= 0) {
            return generatedAt;
        }
        if (ahead > LOGGED_AHEAD && known.test(producerId) && due(producerId, received.mono())) {
            LOG.warn(
                    "Producer {} stamped a message {}, {} ms ahead of its clock now, {}: its marks are taken from"
                            + " the clock's now, not from the stamp",
                    producerId,
                    Instant.ofEpochMilli(generatedAt),
                    ahead,
                    Instant.ofEpochMilli(now));
        }
        return now;
    }

    /** The largest offset the producer keeps, as of a reading; none before its first alive. */
    private long cautious(long producerId, Stamp reading) {
        var offsets = kept.get(producerId);
        if (offsets == null) {
            return 0;
        }
        long largest = Long.MIN_VALUE;
        for (var measured : offsets) {
            largest = Math.max(largest, measured.offset().at(reading));
        }
        return largest;
    }

    /**
     * The producer's now when the SDK received a message, by the smallest offset of the last minute:
     * the latest the producer's clock can be taken to read, which is what the live state asks to
     * tell a stamp or a watermark ahead of it. The largest offset would put it early by the longest
     * delivery of the minute, and a single late alive would put it minutes early, so right stamps
     * would look ahead and bad ones would pass; "no offset" does not count, only alives do; an alive stamped ahead puts it late for the minute
     * it stays, which only delays the live state's recovery from a bad stamp by that long.
     *
     * @param received when the SDK received the message, both readings of its clock
     */
    long producerNow(long producerId, Stamp received) {
        var offsets = kept.get(producerId);
        if (offsets == null) {
            return received.wall();
        }
        long smallest = Long.MAX_VALUE;
        for (var measured : offsets) {
            if (measured.mono() != BEFORE_ANY_ALIVE) {
                smallest = Math.min(smallest, measured.offset().at(received));
            }
        }
        return received.wall() - smallest;
    }

    /** Whether a log line is due for the producer at {@code mono}; if it is, it is taken. */
    private boolean due(long producerId, long mono) {
        var due = new boolean[1];
        loggedAt.compute(producerId, (id, last) -> {
            due[0] = last == null || mono - last >= LOG_EVERY;
            return due[0] ? mono : last;
        });
        return due[0];
    }
}
