package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Three refusals within a minute of each other, since the last connection, end the reconnecting;
 * the minute by {@link System#nanoTime}, which the test sets.
 */
class RefusalsTest {

    private long now = 1_000_000_000L;
    private final Refusals refusals = new Refusals(3, Duration.ofMinutes(1), () -> now);

    private void advance(Duration by) {
        now += by.toNanos();
    }

    @Test
    void threeInAFewSecondsAreNotEnough() {
        // the production backoff tries again after 1, 2 and 4 s
        assertThat(refusals.refusedTooOften()).isFalse();
        advance(Duration.ofSeconds(1));
        assertThat(refusals.refusedTooOften()).isFalse();
        advance(Duration.ofSeconds(2));
        assertThat(refusals.refusedTooOften()).as("three, but over 3 s only").isFalse();
        advance(Duration.ofSeconds(4));
        assertThat(refusals.refusedTooOften()).isFalse();
    }

    @Test
    void refusalsThatGoOnForTheWindowAreEnough() {
        refusals.refusedTooOften();
        advance(Duration.ofSeconds(30));
        refusals.refusedTooOften();
        advance(Duration.ofSeconds(30));
        assertThat(refusals.refusedTooOften())
                .as("three, over the whole minute")
                .isTrue();
        assertThat(refusals.count()).isEqualTo(3);
        assertThat(refusals.span()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theWindowIsMeasuredByDifferenceSoATickerThatWrapsAroundKeepsIt() {
        // nanoTime's origin is arbitrary: a reading may pass Long.MAX_VALUE and wrap to negative
        now = Long.MAX_VALUE - Duration.ofSeconds(10).toNanos();
        refusals.refusedTooOften();
        advance(Duration.ofSeconds(30));
        assertThat(refusals.refusedTooOften()).isFalse();
        advance(Duration.ofSeconds(29));
        assertThat(refusals.refusedTooOften()).as("three, over 59 s").isFalse();
        advance(Duration.ofSeconds(1));
        assertThat(refusals.refusedTooOften()).as("four, over the minute").isTrue();
        assertThat(refusals.span()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void fewerThanEnoughAreNotEnoughHoweverLong() {
        refusals.refusedTooOften();
        advance(Duration.ofMinutes(2));
        assertThat(refusals.refusedTooOften()).as("two, over two minutes").isFalse();
    }

    @Test
    void aConnectionInBetweenStartsTheCountAgain() {
        refusals.refusedTooOften();
        refusals.refusedTooOften();
        advance(Duration.ofSeconds(61));
        refusals.clear();
        assertThat(refusals.refusedTooOften()).isFalse();
        assertThat(refusals.count()).isEqualTo(1);
        assertThat(refusals.span()).isZero();
    }
}
