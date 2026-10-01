package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

/** Three refusals within a minute of each other, since the last connection, end the reconnecting. */
class RefusalsTest {

    private Instant now = Instant.parse("2026-09-30T12:00:00Z");
    private final InstantSource clock = () -> now;
    private final Refusals refusals = new Refusals(3, Duration.ofMinutes(1), clock);

    @Test
    void threeInAFewSecondsAreNotEnough() {
        // the production backoff tries again after 1, 2 and 4 s
        assertThat(refusals.refusedTooOften()).isFalse();
        now = now.plusSeconds(1);
        assertThat(refusals.refusedTooOften()).isFalse();
        now = now.plusSeconds(2);
        assertThat(refusals.refusedTooOften()).as("three, but over 3 s only").isFalse();
        now = now.plusSeconds(4);
        assertThat(refusals.refusedTooOften()).isFalse();
    }

    @Test
    void refusalsThatGoOnForTheWindowAreEnough() {
        refusals.refusedTooOften();
        now = now.plusSeconds(30);
        refusals.refusedTooOften();
        now = now.plusSeconds(30);
        assertThat(refusals.refusedTooOften())
                .as("three, over the whole minute")
                .isTrue();
        assertThat(refusals.count()).isEqualTo(3);
        assertThat(refusals.span()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void fewerThanEnoughAreNotEnoughHoweverLong() {
        refusals.refusedTooOften();
        now = now.plusSeconds(120);
        assertThat(refusals.refusedTooOften()).as("two, over two minutes").isFalse();
    }

    @Test
    void aConnectionInBetweenStartsTheCountAgain() {
        refusals.refusedTooOften();
        refusals.refusedTooOften();
        now = now.plusSeconds(61);
        refusals.clear();
        assertThat(refusals.refusedTooOften()).isFalse();
        assertThat(refusals.count()).isEqualTo(1);
    }
}
