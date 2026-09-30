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
    void threeWithinAMinuteAreEnough() {
        assertThat(refusals.refusedTooOften()).isFalse();
        now = now.plusSeconds(20);
        assertThat(refusals.refusedTooOften()).isFalse();
        now = now.plusSeconds(20);
        assertThat(refusals.refusedTooOften()).isTrue();
    }

    @Test
    void oneOlderThanAMinuteNoLongerCounts() {
        refusals.refusedTooOften();
        refusals.refusedTooOften();
        now = now.plusSeconds(61);
        assertThat(refusals.refusedTooOften())
                .as("the first two are over a minute old")
                .isFalse();
    }

    @Test
    void aConnectionInBetweenStartsTheCountAgain() {
        refusals.refusedTooOften();
        refusals.clear();
        refusals.refusedTooOften();
        assertThat(refusals.refusedTooOften()).isFalse();
    }
}
