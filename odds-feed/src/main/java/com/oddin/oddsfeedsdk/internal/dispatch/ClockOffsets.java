package com.oddin.oddsfeedsdk.internal.dispatch;

import com.oddin.oddsfeedsdk.internal.clock.ClockOffset;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
import com.oddin.oddsfeedsdk.internal.producer.Producers;
import java.time.Duration;
import java.time.Instant;
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
 * safe points, the live state's watermarks - see {@link #mark}: both dispatchers ask it, so the
 * recovery actor and the live state take the same stamp from one message. Safe for concurrent use.
 */
public final class ClockOffsets {

    private static final Logger LOG = LoggerFactory.getLogger(ClockOffsets.class);

    /** How far ahead of its producer's now a stamp has to be to be logged: below it is jitter. */
    private static final long LOGGED_AHEAD = Duration.ofSeconds(1).toMillis();

    /** How often a producer's stamps ahead are logged, at most. */
    private static final long LOG_EVERY = Duration.ofMinutes(1).toMillis();

    private final LongPredicate known;
    private final Map<Long, ClockOffset> offsets = new ConcurrentHashMap<>();
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
        if (known.test(producerId)) {
            offsets.put(producerId, ClockOffset.measured(generatedAt, received));
        }
    }

    /**
     * How old a message of the producer is: from its timestamp to {@code takenAt}, corrected by the
     * producer's offset. Before the producer's first alive its offset is taken as none, so the age is
     * read by the two clocks as they are.
     */
    long age(long producerId, long generatedAt, long takenAt) {
        var offset = offsets.get(producerId);
        return takenAt - generatedAt - (offset == null ? 0 : offset.offset());
    }

    /**
     * The stamp the SDK takes a mark from, for a message of the producer: its timestamp, but no later
     * than the producer's now at receipt - the SDK's wall clock then, less the producer's offset, as of
     * the reading and by the offset known before this message, so an alive does not vouch for its own
     * stamp. A component with a fast clock, or a bad value, cannot move a checkpoint or a watermark
     * past what the producer has sent: that would leave a gap's start too late to ask for what lies
     * before it, or hold later messages with right stamps back as older. Before the producer's first
     * alive its offset is taken as none. Every message is clamped; a stamp more than a second ahead
     * is also logged, once a minute per producer. What the client sees of the message stays as it is.
     *
     * @param generatedAt the message's timestamp, epoch millis by the producer's clock
     * @param received when the SDK received it, both readings of its clock
     */
    long mark(long producerId, long generatedAt, Stamp received) {
        var offset = offsets.get(producerId);
        long now = received.wall() - (offset == null ? 0 : offset.at(received));
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
