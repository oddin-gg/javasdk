package com.oddin.oddsfeedsdk.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(feedWrite("m1", LIVE, 2_000, FRESH, now())).isTrue();
        assertThat(feedWrite("m1", LIVE, 1_000, FRESH, now())).as("older").isFalse();
        assertThat(feedWrite("m1", LIVE, 2_000, FRESH, now()))
                .as("the same again")
                .isTrue();
        assertThat(feedWrite("m1", LIVE, 3_000, FRESH, now())).as("newer").isTrue();
        assertThat(feedWrite("m1", LIVE, 2_500, FRESH, now()))
                .as("older than the newest")
                .isFalse();
    }

    @Test
    void eachProducerHasItsOwnWatermark() {
        assertThat(feedWrite("m1", LIVE, 5_000, FRESH, now())).isTrue();
        assertThat(feedWrite("m1", PREMATCH, 1_000, FRESH, now()))
                .as("the clocks of two producers are not compared")
                .isTrue();
        assertThat(feedWrite("m2", LIVE, 1_000, FRESH, now()))
                .as("another match")
                .isTrue();
    }

    @Test
    void aMessageOlderThanTheStatusAgeWritesNothingAndLeavesNoWatermark() {
        Duration backlog = FeedWatermarks.STATUS_AGE.plusSeconds(1);
        assertThat(feedWrite("m1", LIVE, 9_000, backlog, now())).isFalse();
        assertThat(restWrite("m1")).as("no watermark was left").isTrue();
        assertThat(feedWrite("m1", LIVE, 1_000, FeedWatermarks.STATUS_AGE, now()))
                .as("exactly the status age still writes")
                .isTrue();
    }

    @Test
    void restWritesOnlyOnceTheFeedHasBeenQuietForTheStatusAge() {
        assertThat(restWrite("m1")).as("the feed never wrote").isTrue();
        feedWrite("m1", LIVE, 1_000, FRESH, now());
        assertThat(restWrite("m1")).isFalse();

        time.advance(Duration.ofMinutes(19));
        feedWrite("m1", PREMATCH, 1_000, FRESH, now());
        time.advance(Duration.ofMinutes(2));
        assertThat(restWrite("m1"))
                .as("the prematch producer wrote 2 minutes ago")
                .isFalse();
        time.advance(Duration.ofMinutes(19));
        assertThat(restWrite("m1")).as("both quiet for over 20 minutes").isTrue();
    }

    @Test
    void aWatermarkOutlivesTheStatusByADayAndTheRecordIsBounded() {
        feedWrite("m1", LIVE, 5_000, FRESH, now());
        time.advance(Duration.ofHours(23));
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH, now()))
                .as("still remembered")
                .isFalse();
        time.advance(Duration.ofHours(2));
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH, now()))
                .as("forgotten after 24 hours")
                .isTrue();

        var bounded = new FeedWatermarks<String>(10, time);
        for (int i = 0; i < 100; i++) {
            bounded.feedWriteIfNewer("m" + i, LIVE, 1, FRESH, now(), () -> {});
        }
        assertThat(bounded.size()).isEqualTo(10);
    }

    @Test
    void anEntityEvictedForRoomWhileTheFeedOwnsItIsStillOwnedByTheFeed() {
        var bounded = new FeedWatermarks<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), () -> {});
        for (int i = 0; i < 10_000 && bounded.holds("live"); i++) {
            bounded.feedWriteIfNewer("other " + i, LIVE, 1, FRESH, now(), () -> {});
        }
        assertThat(bounded.holds("live")).as("evicted for room").isFalse();

        var ran = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), ran::incrementAndGet))
                .as("an older message from the producer still does not write")
                .isFalse();
        assertThat(ran).hasValue(0);
        assertThat(bounded.restWriteIfQuiet("live", now(), () -> {}))
                .as("the feed wrote it a moment ago")
                .isFalse();
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 6_000, FRESH, now(), () -> {}))
                .isTrue();

        time.advance(FeedWatermarks.STATUS_AGE.plusMinutes(1));
        assertThat(bounded.restWriteIfQuiet("live", now(), () -> {}))
                .as("quiet for longer than the status age")
                .isTrue();
    }

    @Test
    void aWriteThatThrowsLeavesAnEvictedEntityOwnedByTheFeed() {
        var bounded = new FeedWatermarks<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), () -> {});
        for (int i = 0; i < 10_000 && bounded.holds("live"); i++) {
            bounded.feedWriteIfNewer("other " + i, LIVE, 1, FRESH, now(), () -> {});
        }
        assertThat(bounded.holds("live")).isFalse();
        assertThatThrownBy(() -> bounded.feedWriteIfNewer("live", LIVE, 6_000, FRESH, now(), () -> {
                    throw new IllegalStateException("the cache write failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bounded.restWriteIfQuiet("live", now(), () -> {}))
                .as("still the feed's")
                .isFalse();
    }

    @Test
    void twoLiveMessagesForOneEntityWriteOneAfterTheOther() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var older = threads.submit(() -> marks.feedWriteIfNewer("m1", LIVE, 2_000, FRESH, now(), () -> {
                writing.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add(2_000L);
            }));
            assertThat(writing.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var newer = threads.submit(
                    () -> marks.feedWriteIfNewer("m1", LIVE, 3_000, FRESH, now(), () -> order.add(3_000L)));
            try {
                Thread.sleep(200);
                assertThat(newer.isDone())
                        .as("the newer waits for the older's write")
                        .isFalse();
            } finally {
                release.countDown();
            }
            assertThat(older.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(newer.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(order).containsExactly(2_000L, 3_000L);
        assertThat(feedWrite("m1", LIVE, 2_500, FRESH, now()))
                .as("older than the newest")
                .isFalse();
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
                boolean admitted = feedWrite("m1", LIVE, 1_000, FRESH, now());
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

    /** A live message's write of the feed's fields, which must run exactly when it is admitted. */
    private boolean feedWrite(String key, long producer, long timestamp, Duration age, Instant receivedAt) {
        var ran = new java.util.concurrent.atomic.AtomicInteger();
        boolean admitted = marks.feedWriteIfNewer(key, producer, timestamp, age, receivedAt, ran::incrementAndGet);
        assertThat(ran.get()).as("the write ran as often as it was admitted").isEqualTo(admitted ? 1 : 0);
        return admitted;
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
