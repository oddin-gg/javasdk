package com.oddin.oddsfeedsdk.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.oddin.oddsfeedsdk.LogCapture;
import com.oddin.oddsfeedsdk.internal.clock.Stamp;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * What the recovery takes a message's stamp as: the stamp itself, unless it is more than a second
 * ahead of its producer's now at receipt, by the most cautious offset of the minute, and then that
 * now.
 */
class ClockOffsetsTest {

    private static final long TEN_MINUTES = Duration.ofMinutes(10).toMillis();
    private static final long MINUTE = Duration.ofMinutes(1).toMillis();

    /** Producers 1 and 2. */
    private final ClockOffsets offsets = new ClockOffsets(id -> id == 1 || id == 2);

    @Test
    void aStampNotAheadOfTheProducersNowIsItsOwnMark() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        // the producer's clock is 2.5 s behind: its now at receipt is 107 500 by it
        assertThat(offsets.mark(1, 107_500, Stamp.of(110_000)))
                .as("exactly its now")
                .isEqualTo(107_500);
        assertThat(offsets.mark(1, 90_000, Stamp.of(110_000))).as("older").isEqualTo(90_000);
    }

    @Test
    void aStampAheadByNoMoreThanASecondIsTakenAsItIs() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        assertThat(offsets.mark(1, 107_500 + 500, Stamp.of(110_000)))
                .as("500 ms ahead")
                .isEqualTo(108_000);
        assertThat(offsets.mark(1, 107_500 + 1_000, Stamp.of(110_000)))
                .as("a second ahead")
                .isEqualTo(108_500);
        assertThat(offsets.mark(1, 107_500 + 1_001, Stamp.of(110_000)))
                .as("more than a second: the producer's now")
                .isEqualTo(107_500);
    }

    /**
     * An alive that came later than the others, or stamped behind, shows a larger offset, and the
     * most cautious one clamps for a minute. Within a second of it an ordinary stamp is kept; beyond,
     * it is lowered to the producer's now by that offset, which only makes a recovery larger.
     */
    @Test
    void aLateAliveDoesNotLowerAnOrdinaryMarkByASecondOrLessAndLowersItByNoMoreThanItsDelayBeyond() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        offsets.alive(1, 105_000, Stamp.of(105_000 + 2_500 + 800));
        long ordinary = 110_000 - 2_500;
        assertThat(offsets.mark(1, ordinary, Stamp.of(110_000)))
                .as("0.8 s late: within the second")
                .isEqualTo(ordinary);

        offsets.alive(1, 115_000, Stamp.of(115_000 + 2_500 + 3_000));
        assertThat(offsets.mark(1, ordinary, Stamp.of(110_000)))
                .as("3 s late: the producer's now by its offset, 3 s below the stamp, never above it")
                .isEqualTo(ordinary - 3_000);
    }

    @Test
    void aStampAheadOfTheProducersNowIsTakenAsItsNowByTheOffsetOfItsAlives() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        assertThat(offsets.mark(1, 110_000 + TEN_MINUTES, Stamp.of(110_000))).isEqualTo(107_500);
        assertThat(offsets.mark(2, 110_000 + TEN_MINUTES, Stamp.of(110_000)))
                .as("the other producer has no offset yet: the SDK's own clock")
                .isEqualTo(110_000);
    }

    /** The alive dispatcher keeps an alive's offset before the session's copy of it is handled. */
    @Test
    void theSessionsCopyOfAnAliveStampedAheadIsClampedWhateverOffsetItsOwnShowed() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        long ahead = 110_000 + TEN_MINUTES;
        // the alive dispatcher kept this alive's own offset: the producer's clock 10 minutes ahead
        offsets.alive(1, ahead, Stamp.of(110_000));
        assertThat(offsets.mark(1, ahead, Stamp.of(110_000)))
                .as("by the offset of the alive before, 2.5 s behind")
                .isEqualTo(107_500);
    }

    @Test
    void beforeTheFirstAliveOfAProducerTheOffsetIsNoneAndTheSdksOwnClockClampsTheStamp() {
        assertThat(offsets.mark(2, 110_000 + TEN_MINUTES, Stamp.of(110_000))).isEqualTo(110_000);
        assertThat(offsets.mark(2, 110_000 + 1_000, Stamp.of(110_000)))
                .as("within a second")
                .isEqualTo(111_000);
    }

    @Test
    void aMessageReceivedBeforeAnAliveStampedAheadIsClampedWhenItIsHandledAfterIt() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        // a message received at 120 000, stamped ahead, waits behind a callback; an alive follows
        offsets.alive(1, 121_000 + TEN_MINUTES, Stamp.of(121_000));
        assertThat(offsets.mark(1, 120_000 + TEN_MINUTES, Stamp.of(120_000)))
                .as("the producer's now at its receipt, by the offset of the alive before the later one")
                .isEqualTo(117_500);
    }

    @Test
    void aProducerClockThatStepsBackIsTakenAtOnce() {
        offsets.alive(1, 1_000_000, Stamp.of(1_002_500));
        // the producer's clock goes back 10 minutes: its next alive shows an offset 10 minutes more
        offsets.alive(1, 1_012_500 - 602_500, Stamp.of(1_012_500));
        long stampedByTheOldClock = 1_013_000 - 2_500;
        assertThat(offsets.mark(1, stampedByTheOldClock, Stamp.of(1_013_000)))
                .as("clamped to the producer's now by the new offset")
                .isEqualTo(1_013_000 - 602_500);
    }

    @Test
    void aProducerClockThatStepsForwardIsTakenAMinuteLater() {
        offsets.alive(1, 100_000, Stamp.of(102_500));
        // the producer's clock goes 10 minutes forward: its alives, every ten seconds, show an offset
        // 10 minutes less, and a message stamped by it is ahead of the SDK's own now
        long received = 100_000;
        for (int i = 1; i <= 7; i++) {
            received = 100_000 + 10_000L * i;
            offsets.alive(1, received + TEN_MINUTES, Stamp.of(received));
        }
        assertThat(offsets.mark(1, received + TEN_MINUTES, Stamp.of(received)))
                .as("the offset before the change still clamps within the window")
                .isEqualTo(received - 2_500);

        received = 180_000;
        offsets.alive(1, received + TEN_MINUTES, Stamp.of(received));
        assertThat(offsets.mark(1, received + TEN_MINUTES, Stamp.of(received)))
                .as("a minute after the first alive of the new clock, it is the producer's now")
                .isEqualTo(received + TEN_MINUTES);
    }

    @Test
    void aStepOfTheWallClockAfterTheOffsetWasMeasuredDoesNotLowerTheMarks() {
        // measured when the wall clock read 1 000 000 and the monotonic one 500 000
        offsets.alive(1, 1_000_000, new Stamp(1_000_000, 500_000));
        // the wall clock then steps back an hour; the producer's clock does not
        long hour = Duration.ofHours(1).toMillis();
        var later = new Stamp(1_010_000 - hour, 510_000);
        assertThat(offsets.mark(1, 1_010_000, later))
                .as("not clamped an hour down")
                .isEqualTo(1_010_000);
        assertThat(offsets.mark(1, 1_010_000 + TEN_MINUTES, later))
                .as("still clamped to the producer's now")
                .isEqualTo(1_010_000);
    }

    @Test
    void aStampAheadByMoreThanASecondIsLoggedOncePerProducerPerMinute() {
        try (var log = LogCapture.of(ClockOffsets.class)) {
            offsets.alive(1, 100_000, Stamp.of(100_000));
            offsets.alive(2, 100_000, Stamp.of(100_000));
            offsets.mark(1, 200_000 + 1_000, Stamp.of(200_000));
            assertThat(log.lines()).as("a second ahead is jitter").isEmpty();

            offsets.mark(1, 200_000 + 1_001, Stamp.of(200_000));
            assertThat(log.lines()).hasSize(1);
            assertThat(log.lines().getFirst())
                    .startsWith("WARN Producer 1 stamped a message ")
                    .contains("1970-01-01T00:03:21.001Z")
                    .contains("1001 ms ahead of its clock now, 1970-01-01T00:03:20Z");

            for (int i = 0; i < 50; i++) {
                offsets.mark(1, 300_000 + TEN_MINUTES, Stamp.of(200_000 + i));
            }
            assertThat(log.lines()).as("not per message").hasSize(1);

            offsets.mark(2, 200_000 + TEN_MINUTES, Stamp.of(200_000 + 5));
            assertThat(log.lines()).as("the other producer's own").hasSize(2);

            offsets.mark(1, 300_000 + TEN_MINUTES, Stamp.of(200_000 + MINUTE - 1));
            assertThat(log.lines()).as("a minute not over").hasSize(2);
            offsets.mark(1, 300_000 + TEN_MINUTES, Stamp.of(200_000 + MINUTE));
            assertThat(log.lines()).as("a minute over").hasSize(3);
        }
    }

    @Test
    void aStampAheadOfAProducerTheListDoesNotHaveIsClampedAndNotLogged() {
        try (var log = LogCapture.of(ClockOffsets.class, Level.WARN)) {
            assertThat(offsets.mark(7, 10_000 + TEN_MINUTES, Stamp.of(10_000))).isEqualTo(10_000);
            assertThat(log.lines()).isEmpty();
        }
    }
}
