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
        assertThat(restWrite("m1")).as("no watermark was left").isTrue();
        assertThat(marks.feedMayWrite("m1", LIVE, 1_000, FeedWatermarks.STATUS_AGE, now()))
                .as("exactly the status age still writes")
                .isTrue();
    }

    @Test
    void restWritesOnlyOnceTheFeedHasBeenQuietForTheStatusAge() {
        assertThat(restWrite("m1")).as("the feed never wrote").isTrue();
        marks.feedMayWrite("m1", LIVE, 1_000, FRESH, now());
        assertThat(restWrite("m1")).isFalse();

        time.advance(Duration.ofMinutes(19));
        marks.feedMayWrite("m1", PREMATCH, 1_000, FRESH, now());
        time.advance(Duration.ofMinutes(2));
        assertThat(restWrite("m1"))
                .as("the prematch producer wrote 2 minutes ago")
                .isFalse();
        time.advance(Duration.ofMinutes(19));
        assertThat(restWrite("m1")).as("both quiet for over 20 minutes").isTrue();
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

    @Test
    void aLiveMessageWaitsForARestWriteInProgressAndThenOverwritesIt() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var rest = threads.submit(() -> marks.restWriteIfQuiet("m1", now(), () -> {
                writing.countDown();
                try {
                    // bounded, so a failed assertion below cannot leave the executor waiting for ever
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("rest");
            }));
            assertThat(writing.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var feed = threads.submit(() -> {
                boolean admitted = marks.feedMayWrite("m1", LIVE, 1_000, FRESH, now());
                order.add("feed");
                return admitted;
            });
            try {
                Thread.sleep(200);
                assertThat(feed.isDone())
                        .as("the feed waits for the REST write on the entity")
                        .isFalse();
            } finally {
                release.countDown();
            }
            assertThat(rest.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(feed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(order).containsExactly("rest", "feed");
        assertThat(restWrite("m1")).as("the feed owns it now").isFalse();
    }

    /** A REST write of the feed's fields, which must run exactly when it is admitted. */
    private boolean restWrite(String key) {
        var ran = new java.util.concurrent.atomic.AtomicInteger();
        boolean admitted = marks.restWriteIfQuiet(key, now(), ran::incrementAndGet);
        assertThat(ran.get()).as("the write ran as often as it was admitted").isEqualTo(admitted ? 1 : 0);
        return admitted;
    }

    private Instant now() {
        return time.instant();
    }
}
