package com.oddin.oddsfeedsdk.internal.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedClockTest {

    private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
    private static final long MILLI = 1_000_000L;

    private final AtomicReference<Instant> wall = new AtomicReference<>(START);
    private final AtomicLong nanos = new AtomicLong();
    private final InstantSource wallSource = wall::get;

    @ParameterizedTest
    @ValueSource(longs = {0, 1, -1, 123_456_789L, -123_456_789L, Long.MIN_VALUE, Long.MAX_VALUE})
    void theMonotonicSideStartsAtOneWhateverTheRawClockStartsAt(long origin) {
        nanos.set(origin);
        var clock = FeedClock.of(wallSource, nanos::get);
        assertThat(clock.monoMillis()).isEqualTo(1);
        assertThat(clock.stamp().mono()).isEqualTo(1);
        nanos.addAndGet(5 * MILLI);
        assertThat(clock.monoMillis()).isEqualTo(6);
    }

    @Test
    void theMonotonicSideIsNeverZeroAsTheRawClockPassesZeroOrWraps() {
        nanos.set(-2 * MILLI);
        var clock = FeedClock.of(wallSource, nanos::get);
        var seen = new long[4];
        for (int i = 0; i < seen.length; i++) {
            seen[i] = clock.monoMillis();
            nanos.addAndGet(MILLI);
        }
        assertThat(seen).containsExactly(1, 2, 3, 4);

        nanos.set(Long.MAX_VALUE - MILLI);
        var wrapping = FeedClock.of(wallSource, nanos::get);
        nanos.addAndGet(3 * MILLI);
        assertThat(nanos.get()).as("the raw clock wrapped").isNegative();
        assertThat(wrapping.monoMillis())
                .as("a duration is a difference, so the wrap shows nothing")
                .isEqualTo(4);
    }

    @Test
    void theMonotonicSideCountsWholeMillisOfTheRawClock() {
        var clock = FeedClock.of(wallSource, nanos::get);
        nanos.set(MILLI - 1);
        assertThat(clock.monoMillis()).isEqualTo(1);
        nanos.set(MILLI);
        assertThat(clock.monoMillis()).isEqualTo(2);
        nanos.set(90 * 1_000 * MILLI);
        assertThat(clock.monoMillis()).isEqualTo(90_001);
    }

    @Test
    void aStampReadsBothSides() {
        nanos.set(77 * MILLI);
        var clock = FeedClock.of(wallSource, nanos::get);
        nanos.addAndGet(1_500 * MILLI);
        assertThat(clock.stamp()).isEqualTo(new Stamp(START.toEpochMilli(), 1_501));
        assertThat(clock.wallMillis()).isEqualTo(START.toEpochMilli());
        assertThat(clock.wall().instant()).isEqualTo(START);
    }

    @Test
    void aStepOfTheWallClockLeavesTheMonotonicSideWhereItIs() {
        var clock = FeedClock.of(wallSource, nanos::get);
        nanos.addAndGet(10_000 * MILLI);
        var before = clock.stamp();

        wall.set(START.plus(Duration.ofSeconds(30)));
        var forward = clock.stamp();
        assertThat(forward.wall() - before.wall()).isEqualTo(30_000);
        assertThat(forward.mono()).isEqualTo(before.mono());

        wall.set(START.minus(Duration.ofSeconds(30)));
        var back = clock.stamp();
        assertThat(back.wall() - before.wall()).isEqualTo(-30_000);
        assertThat(back.mono()).isEqualTo(before.mono());
    }

    @Test
    void theRawClockMovingLeavesTheWallSideWhereItIs() {
        var clock = FeedClock.of(wallSource, nanos::get);
        var before = clock.stamp();
        nanos.addAndGet(60_000 * MILLI);
        var later = clock.stamp();
        assertThat(later.wall()).isEqualTo(before.wall());
        assertThat(later.mono() - before.mono()).isEqualTo(60_000);
    }

    @Test
    void aFollowingClockMovesBothSidesTogetherAndReadsTheSameNumber() {
        var moving = new AtomicLong(1_000);
        var clock = FeedClock.following(() -> Instant.ofEpochMilli(moving.get()));
        assertThat(clock.stamp()).isEqualTo(Stamp.of(1_000));
        moving.set(61_000);
        assertThat(clock.stamp()).isEqualTo(new Stamp(61_000, 61_000));
        assertThat(clock.monoMillis()).isEqualTo(clock.wallMillis());
    }

    @Test
    void theMonotonicReadingInNanosIsTheMillisAndIgnoresTheWallClock() {
        var clock = FeedClock.of(wallSource, nanos::get);
        nanos.addAndGet(5 * MILLI);
        wall.set(START.plus(Duration.ofHours(3)));
        assertThat(clock.monoNanos()).isEqualTo(6 * MILLI);
        assertThat(FeedClock.following(() -> Instant.ofEpochMilli(2_000)).monoNanos())
                .isEqualTo(2_000 * MILLI);
    }

    @Test
    void theSystemClockReadsBothSidesAndTheMonotonicOneNeverGoesBack() {
        var clock = FeedClock.system();
        var first = clock.stamp();
        var second = clock.stamp();
        assertThat(first.mono()).isPositive();
        assertThat(second.mono()).isGreaterThanOrEqualTo(first.mono());
        assertThat(first.wall()).isCloseTo(System.currentTimeMillis(), offset(5_000L));
    }
}
