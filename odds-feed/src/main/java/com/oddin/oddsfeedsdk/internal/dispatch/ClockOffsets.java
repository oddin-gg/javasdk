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
 * <p>It also tells what the recovery may take a message's stamp as - its checkpoints, safe points
 * and gap starts - see {@link #mark}: both dispatchers ask it, so the recovery actor takes one
 * mark from one message. A stamp more than a second ahead of its producer's now is clamped by the
 * most cautious offset of the last minute, not the latest one, which an alive stamped ahead has
 * already moved when the message that needs the clamp is handled: the session's copy of that very
 * alive, or a message queued behind a callback while the alive dispatcher went on. Each producer
 * keeps the offsets of its alives over the window, in a small array that is replaced whole, so a
 * reader never waits for the alive dispatcher. Safe for concurrent use.
 */
public final class ClockOffsets {

    private static final Logger LOG = LoggerFactory.getLogger(ClockOffsets.class);

    /**
     * How far ahead of its producer's now a stamp has to be to be clamped, and logged: below it is
     * the jitter of reading two clocks and of a delivery's latency, and the stamp is taken as it is.
     */
    private static final long AHEAD = Duration.ofSeconds(1).toMillis();

    /** How often a producer's stamps ahead are logged, at most. */
    private static final long LOG_EVERY = Duration.ofMinutes(1).toMillis();

    /** How long an offset clamps the marks after it was measured, by the monotonic clock. */
    private static final long WINDOW = Duration.ofMinutes(1).toMillis();

    /**
     * The most offsets a producer keeps: a producer sends an alive every few seconds, so a minute
     * holds a dozen; a faster one gets a shorter window rather than a larger array.
     */
    private static final int KEPT = 64;

    /**
     * How many alives of a producer the mark of the alive channel is kept for, for the sessions'
     * copies of them: a session is behind the channel by what its queue holds, a few alives at most.
     */
    private static final int MARKED = 16;

    /** An offset, with when the alive that showed it was received, on the monotonic clock. */
    private record Measured(ClockOffset offset, long mono) {}

    /** The mark the alive channel took from the alive stamped {@code generatedAt}. */
    private record Marked(long generatedAt, long mark) {}

    private final LongPredicate known;
    /**
     * Each producer's offsets, oldest first: those of the window, and the newest one older than it,
     * which a lone alive after a silence is measured against. None before the first alive, when the
     * offset is none: the SDK's own clock. Replaced whole, never changed.
     */
    private final Map<Long, Measured[]> kept = new ConcurrentHashMap<>();
    /**
     * Each producer's last few alives, with the mark the channel took from them, oldest first, so a
     * session's copy of an alive takes that mark and not one from an offset the alive itself showed.
     * Replaced whole, never changed.
     */
    private final Map<Long, Marked[]> marked = new ConcurrentHashMap<>();
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
                return new Measured[] {measured};
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
     * The stamp the recovery takes a mark from, for a message of the producer: its timestamp, as it
     * is, unless it is more than a second ahead of the producer's now at receipt - the SDK's wall
     * clock then, less the producer's offset, as of the reading - and then that now. A component
     * with a fast clock, or a bad value, cannot move a checkpoint past what the producer has sent:
     * that would leave a gap's start too late to ask for what lies before it. The offset is the most
     * cautious of the last minute's, the largest, which puts the producer's now earliest. An alive
     * stamped ahead shows a small offset, so it does not lift it, and a message received before such
     * an alive and handled after is clamped as it was; the alive does not vouch for its own stamp
     * because its mark is taken before its offset is kept and its session copies take that mark (see
     * {@link #markAlive}). An alive that is late, or stamped behind, shows the largest offset and
     * pulls the now towards it: what follows is marked lower by its delay for the minute. A producer whose clock goes back
     * is taken at once, and its marks go down; one whose clock goes forward, or is really ahead, is
     * taken a minute later. A delivery that took more than a second longer than the longest alive
     * of the minute lowers the mark of what it carries to the producer's now: every error lowers a
     * mark, which only makes a recovery larger. Before the producer's first alive its offset is
     * taken as none. A clamp is logged, once a minute per producer. What the client sees of the
     * message, and the live state, keep the stamp.
     *
     * @param generatedAt the message's timestamp, epoch millis by the producer's clock
     * @param received when the SDK received it, both readings of its clock
     */
    long mark(long producerId, long generatedAt, Stamp received) {
        return clamp(producerId, generatedAt, producerNow(producerId, received), received);
    }

    /**
     * The mark of an alive of the SDK's alive channel, asked before the offset it shows is kept
     * with {@link #alive}, and kept for the sessions' copies of the same alive: see {@link
     * #markAliveCopy}.
     */
    long markAlive(long producerId, long generatedAt, Stamp received) {
        long mark = mark(producerId, generatedAt, received);
        if (known.test(producerId)) {
            marked.compute(producerId, (id, before) -> {
                var all = before == null ? new Marked[1] : Arrays.copyOf(before, before.length + 1);
                all[all.length - 1] = new Marked(generatedAt, mark);
                return all.length <= MARKED ? all : Arrays.copyOfRange(all, all.length - MARKED, all.length);
            });
        }
        return mark;
    }

    /**
     * The mark of a session's copy of an alive: the channel's, when the channel has taken one from
     * an alive of this stamp - the lowest, if it has taken more than one: the same alive delivered
     * twice, a resend, whose later delivery may be marked higher - since by then the offset the
     * alive showed may be kept and, the first alive of
     * a producer above all, would vouch for its own stamp. When the copy is handled first, the
     * channel has not kept the offset yet, so the offset is still the one before the alive - none,
     * the SDK's own clock, for the first alive - and the mark is taken as for any message. The
     * channel keeps the mark before it keeps the offset, and the offset is read here before the
     * mark is looked for, so an offset that includes the alive is never seen without its mark.
     */
    long markAliveCopy(long producerId, long generatedAt, Stamp received) {
        long now = producerNow(producerId, received);
        var marks = marked.get(producerId);
        boolean found = false;
        long smallest = Long.MAX_VALUE;
        if (marks != null) {
            // the same stamp delivered twice, a resend, has two marks: the lower is the safe one
            for (var entry : marks) {
                if (entry.generatedAt() == generatedAt) {
                    found = true;
                    smallest = Math.min(smallest, entry.mark());
                }
            }
        }
        return found ? smallest : clamp(producerId, generatedAt, now, received);
    }

    /** The producer's now when the SDK received a message, by the most cautious offset kept. */
    private long producerNow(long producerId, Stamp received) {
        return received.wall() - cautious(producerId, received);
    }

    /** The stamp, or {@code now} when the stamp is more than a second ahead of it; logged, then. */
    private long clamp(long producerId, long generatedAt, long now, Stamp received) {
        long ahead = generatedAt - now;
        if (ahead <= AHEAD) {
            return generatedAt;
        }
        if (known.test(producerId) && due(producerId, received.mono())) {
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
