package com.oddin.oddsfeedsdk.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Who writes the fields the feed owns: the feed while it is live, REST once it is quiet. */
class FeedWatermarksTest {

    private static final long LIVE = 2;
    private static final long PREMATCH = 1;
    private static final Duration FRESH = Duration.ofSeconds(1);

    private final FakeTime time = new FakeTime();
    private final FeedWatermarks<String> marks = new FeedWatermarks<>(1_000, time);

    @Test
    void anOlderMessageFromTheSameProducerDoesNotWrite() {
        assertThat(marks.feedMayWrite("m1", LIVE, 2_000, FRESH, now())).isTrue();
        assertThat(marks.feedMayWrite("m1", LIVE, 1_000, FRESH, now()))
                .as("older")
                .isFalse();
        assertThat(marks.feedMayWrite("m1", LIVE, 2_000, FRESH, now()))
                .as("the same again")
                .isTrue();
        assertThat(marks.feedMayWrite("m1", LIVE, 3_000, FRESH, now()))
                .as("newer")
                .isTrue();
        assertThat(marks.feedMayWrite("m1", LIVE, 2_500, FRESH, now()))
                .as("older than the newest")
                .isFalse();
    }

    @Test
    void eachProducerHasItsOwnWatermark() {
        assertThat(marks.feedMayWrite("m1", LIVE, 5_000, FRESH, now())).isTrue();
        assertThat(marks.feedMayWrite("m1", PREMATCH, 1_000, FRESH, now()))
                .as("the clocks of two producers are not compared")
                .isTrue();
        assertThat(marks.feedMayWrite("m2", LIVE, 1_000, FRESH, now()))
                .as("another match")
                .isTrue();
    }

    @Test
    void aMessageOlderThanTheStatusAgeWritesNothingAndLeavesNoWatermark() {
        Duration backlog = FeedWatermarks.STATUS_AGE.plusSeconds(1);
        assertThat(marks.feedMayWrite("m1", LIVE, 9_000, backlog, now())).isFalse();
        assertThat(marks.restMayWrite("m1", now())).as("no watermark was left").isTrue();
        assertThat(marks.feedMayWrite("m1", LIVE, 1_000, FeedWatermarks.STATUS_AGE, now()))
                .as("exactly the status age still writes")
                .isTrue();
    }

    @Test
    void restWritesOnlyOnceTheFeedHasBeenQuietForTheStatusAge() {
        assertThat(marks.restMayWrite("m1", now())).as("the feed never wrote").isTrue();
        marks.feedMayWrite("m1", LIVE, 1_000, FRESH, now());
        assertThat(marks.restMayWrite("m1", now())).isFalse();

        time.advance(Duration.ofMinutes(19));
        marks.feedMayWrite("m1", PREMATCH, 1_000, FRESH, now());
        time.advance(Duration.ofMinutes(2));
        assertThat(marks.restMayWrite("m1", now()))
                .as("the prematch producer wrote 2 minutes ago")
                .isFalse();
        time.advance(Duration.ofMinutes(19));
        assertThat(marks.restMayWrite("m1", now()))
                .as("both quiet for over 20 minutes")
                .isTrue();
    }

    @Test
    void aWatermarkOutlivesTheStatusByADayAndTheRecordIsBounded() {
        marks.feedMayWrite("m1", LIVE, 5_000, FRESH, now());
        time.advance(Duration.ofHours(23));
        assertThat(marks.feedMayWrite("m1", LIVE, 4_000, FRESH, now()))
                .as("still remembered")
                .isFalse();
        time.advance(Duration.ofHours(2));
        assertThat(marks.feedMayWrite("m1", LIVE, 4_000, FRESH, now()))
                .as("forgotten after 24 hours")
                .isTrue();

        var bounded = new FeedWatermarks<String>(10, time);
        for (int i = 0; i < 100; i++) {
            bounded.feedMayWrite("m" + i, LIVE, 1, FRESH, now());
        }
        assertThat(bounded.size()).isEqualTo(10);
    }

    private Instant now() {
        return time.instant();
    }
}
