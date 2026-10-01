package com.oddin.oddsfeedsdk.internal.cache;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Who writes the fields the feed owns: the feed while it is live, REST once it is quiet. */
class LiveStateTest {

    private static final long LIVE = 2;
    private static final long PREMATCH = 1;
    private static final Duration FRESH = Duration.ofSeconds(1);
    private static final Field<String> STATUS = Field.shared("status");
    private static final Field<Long> TIMESTAMP = Field.shared("timestamp");
    private static final Field<Integer> HOME_SCORE = Field.shared("homeScore");
    private static final Field<String> CLOCK = Field.shared("clock");

    private final FakeTime time = new FakeTime();
    private final LiveState<String> live = new LiveState<>(1_000, time);

    @Test
    void aMatchWhoseSummaryLoadedBeforeKickOffGoesLiveWithTheFeed() {
        assertThat(live.get("m1")).as("nothing loaded yet").isNull();
        assertThat(live.restWriteIfQuiet("m1", now(), status("not started")))
                .as("the summary, before the feed has said anything")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("not started");

        assertThat(live.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, now(), status("live")))
                .as("kick-off, on the feed")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("live");
        assertThat(live.restWriteIfQuiet("m1", now(), status("not started")))
                .as("a summary from before kick-off, answered late")
                .isFalse();
        assertThat(statusOf("m1")).isEqualTo("live");

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThat(live.restWriteIfQuiet("m1", now(), status("ended")))
                .as("the feed has gone quiet")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("ended");
    }

    @Test
    void liveValuesAreFreshForTheStatusAgeAfterTheFeedOrRestLastWroteThem() {
        live.restWriteIfQuiet("m1", now(), status("not started"));
        assertThat(requireNonNull(live.get("m1")).isFresh(now())).isTrue();
        time.advance(LiveState.STATUS_AGE.plusSeconds(1));
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("the summary is loaded again")
                .isFalse();

        live.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, now(), status("live"));
        time.advance(LiveState.STATUS_AGE.minusSeconds(1));
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("the feed wrote it within the status age")
                .isTrue();
        time.advance(Duration.ofSeconds(2));
        var quiet = requireNonNull(live.get("m1"));
        assertThat(quiet.isFresh(now())).as("the feed went quiet").isFalse();
        assertThat(quiet.get(STATUS)).as("still there to read meanwhile").isEqualTo("live");
    }

    @Test
    void aWriteKeepsWhatItDoesNotCarryAndClearsOnlyWhatItSaysIsGone() {
        live.feedWriteIfNewer(
                "m1",
                LIVE,
                1_000,
                FRESH,
                now(),
                LiveWrite.of().put(STATUS, "live").put(HOME_SCORE, 1).put(CLOCK, "12:00"));
        live.feedWriteIfNewer("m1", LIVE, 2_000, FRESH, now(), LiveWrite.of().put(HOME_SCORE, 2));
        var values = requireNonNull(live.get("m1"));
        assertThat(values.get(STATUS)).as("not carried: kept").isEqualTo("live");
        assertThat(values.get(HOME_SCORE)).isEqualTo(2);
        assertThat(values.get(CLOCK)).isEqualTo("12:00");

        live.feedWriteIfNewer(
                "m1",
                LIVE,
                3_000,
                FRESH,
                now(),
                LiveWrite.of().put(STATUS, "ended").clear(CLOCK));
        values = requireNonNull(live.get("m1"));
        assertThat(values.get(STATUS)).isEqualTo("ended");
        assertThat(values.get(CLOCK)).as("cleared").isNull();
        assertThat(values.get(HOME_SCORE)).isEqualTo(2);
        live.feedWriteIfNewer(
                "m1",
                LIVE,
                4_000,
                FRESH,
                now(),
                LiveWrite.of().put(HOME_SCORE, 3).clear(STATUS));
        assertThat(values.get(HOME_SCORE))
                .as("a snapshot: a later write does not change it")
                .isEqualTo(2);
        assertThat(values.get(STATUS)).isEqualTo("ended");
        assertThat(requireNonNull(live.get("m1")).get(HOME_SCORE)).isEqualTo(3);
    }

    @Test
    void aSummaryFromAnAbandonedFetchDoesNotWrite() {
        live.restWriteIfQuiet("m1", now(), status("ended"), () -> false);
        assertThat(live.restWriteIfQuiet("m1", now(), status("not started"), () -> true))
                .as("its loader replaced it with the fetch that wrote ended")
                .isFalse();
        assertThat(statusOf("m1")).isEqualTo("ended");
    }

    @Test
    void aLiveMessageWaitsForASummaryBeingWrittenAndThenOverwritesIt() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            // the abandoned check runs inside the record's compute: holding it there holds the write
            Future<Boolean> rest =
                    threads.submit(() -> live.restWriteIfQuiet("m1", now(), status("not started"), () -> {
                        writing.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }));
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> feed =
                    threads.submit(() -> live.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, now(), status("live")));
            try {
                Thread.sleep(200);
                assertThat(feed.isDone())
                        .as("the feed waits for the summary's write")
                        .isFalse();
            } finally {
                release.countDown();
            }
            assertThat(rest.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(feed.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(statusOf("m1")).as("the feed's, written after").isEqualTo("live");
        assertThat(live.restWriteIfQuiet("m1", now(), status("not started")))
                .as("and the feed owns it now")
                .isFalse();
    }

    @Test
    void liveStateHasNoValuePerLocale() {
        Field<String> localized = Field.localized("name");
        assertThatThrownBy(() -> LiveWrite.of().put(localized, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LiveWrite.of().clear(localized)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOlderMessageFromTheSameProducerDoesNotWrite() {
        assertThat(feedWrite("m1", LIVE, 2_000, FRESH)).isTrue();
        assertThat(feedWrite("m1", LIVE, 1_000, FRESH)).as("older").isFalse();
        assertThat(feedWrite("m1", LIVE, 2_000, FRESH)).as("the same again").isTrue();
        assertThat(feedWrite("m1", LIVE, 3_000, FRESH)).as("newer").isTrue();
        assertThat(feedWrite("m1", LIVE, 2_500, FRESH))
                .as("older than the newest")
                .isFalse();
        assertThat(timestampOf("m1")).isEqualTo(3_000);
    }

    @Test
    void eachProducerHasItsOwnWatermark() {
        assertThat(feedWrite("m1", LIVE, 5_000, FRESH)).isTrue();
        assertThat(feedWrite("m1", PREMATCH, 1_000, FRESH))
                .as("the clocks of two producers are not compared")
                .isTrue();
        assertThat(feedWrite("m2", LIVE, 1_000, FRESH)).as("another match").isTrue();
    }

    @Test
    void aMessageOlderThanTheStatusAgeWritesNothingAndLeavesNoWatermark() {
        Duration backlog = LiveState.STATUS_AGE.plusSeconds(1);
        assertThat(feedWrite("m1", LIVE, 9_000, backlog)).isFalse();
        assertThat(live.get("m1")).isNull();
        assertThat(restWrite("m1")).as("no watermark was left").isTrue();
        assertThat(feedWrite("m1", LIVE, 1_000, LiveState.STATUS_AGE))
                .as("exactly the status age still writes")
                .isTrue();
    }

    @Test
    void restWritesOnlyOnceTheFeedHasBeenQuietForTheStatusAge() {
        assertThat(restWrite("m1")).as("the feed never wrote").isTrue();
        feedWrite("m1", LIVE, 1_000, FRESH);
        assertThat(restWrite("m1")).isFalse();

        time.advance(Duration.ofMinutes(19));
        feedWrite("m1", PREMATCH, 1_000, FRESH);
        time.advance(Duration.ofMinutes(2));
        assertThat(restWrite("m1"))
                .as("the prematch producer wrote 2 minutes ago")
                .isFalse();
        time.advance(Duration.ofMinutes(19));
        assertThat(restWrite("m1")).as("both quiet for over 20 minutes").isTrue();
        assertThat(feedWrite("m1", LIVE, 900, FRESH))
                .as("REST taking over does not forget the watermarks")
                .isFalse();
    }

    @Test
    void aRecordOutlivesTheStatusByADayAndIsBounded() {
        feedWrite("m1", LIVE, 5_000, FRESH);
        time.advance(Duration.ofHours(23));
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH)).as("still remembered").isFalse();
        time.advance(Duration.ofHours(2));
        assertThat(live.get("m1")).as("forgotten after 24 hours").isNull();
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH)).isTrue();

        var bounded = new LiveState<String>(10, time);
        for (int i = 0; i < 100; i++) {
            bounded.restWriteIfQuiet("m" + i, now(), status("not started"));
        }
        assertThat(bounded.size()).isEqualTo(10);
        assertThat(bounded.keptAside()).as("the feed owned none of them").isZero();
    }

    @Test
    void anEntityEvictedForRoomWhileTheFeedOwnsItIsStillTheFeeds() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        evict(bounded, "live");

        assertThat(requireNonNull(bounded.get("live")).get(STATUS))
                .as("read from where it was kept aside")
                .isEqualTo("live");
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), status("older")))
                .as("an older message from the producer still does not write")
                .isFalse();
        assertThat(bounded.restWriteIfQuiet("live", now(), status("not started")))
                .as("the feed wrote it a moment ago")
                .isFalse();
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 6_000, FRESH, now(), status("ended")))
                .isTrue();
        assertThat(requireNonNull(bounded.get("live")).get(STATUS)).isEqualTo("ended");
        assertThat(bounded.keptAside()).as("taken back by the write").isZero();

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThat(bounded.restWriteIfQuiet("live", now(), status("closed")))
                .as("quiet for longer than the status age")
                .isTrue();
    }

    @Test
    void aWriteThatThrowsLeavesAnEntityKeptAsideAsItWas() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        evict(bounded, "live");
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThatThrownBy(() -> bounded.restWriteIfQuiet("live", now(), status("ended"), () -> {
                    throw new IllegalStateException("the loader failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bounded.keptAside()).as("still kept aside").isEqualTo(1);
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), status("older")))
                .as("its watermark is still there")
                .isFalse();
    }

    @Test
    void anEntityKeptAsideCanBeReadWhileAWriteTakesItBack() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        evict(bounded, "live");
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        var readMeanwhile = new java.util.concurrent.atomic.AtomicReference<LiveState.@Nullable LiveValues>();
        // the abandoned check runs inside the record's compute, as the write takes the record back
        assertThat(bounded.restWriteIfQuiet("live", now(), status("ended"), () -> {
                    readMeanwhile.set(bounded.get("live"));
                    return false;
                }))
                .isTrue();
        assertThat(requireNonNull(readMeanwhile.get()).get(STATUS))
                .as("still read from where it was kept aside")
                .isEqualTo("live");
        assertThat(bounded.keptAside())
                .as("let go of once the write was in place")
                .isZero();
        assertThat(statusOf(bounded, "live")).isEqualTo("ended");
    }

    @Test
    void anEntityKeptAsideThatARejectedWriteTookBackIsStillTheFeedsAfterAnotherEviction() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        evict(bounded, "live");
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), status("older")))
                .isFalse();
        evict(bounded, "live");
        assertThat(bounded.restWriteIfQuiet("live", now(), status("not started")))
                .as("its watermarks were not lost on the way")
                .isFalse();
        assertThat(statusOf(bounded, "live")).isEqualTo("live");
    }

    @Test
    void aRecordKeptAsideAgesOutLikeOneInTheRecord() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        evict(bounded, "live");
        time.advance(LiveState.AGE.plusMinutes(1));
        assertThat(bounded.get("live")).isNull();
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 1_000, FRESH, now(), status("live again")))
                .as("its watermark aged out with it")
                .isTrue();
    }

    @Test
    void whatIsKeptAsideIsBoundedPrunedSparinglyAndWhatDoesNotFitIsCounted() {
        var bounded = new LiveState<String>(16, time);
        // four times as many live entities as the record holds, all written within the status age
        for (int i = 0; i < 64; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.size()).isEqualTo(16);
        assertThat(bounded.keptAside()).as("bounded like the record").isLessThanOrEqualTo(16);
        assertThat(bounded.dropped()).as("what did not fit, counted").isPositive();
        // one scan when it first filled, then one per two records kept aside (16 / 8): not one per eviction
        long prunes = bounded.prunes();
        assertThat(prunes).isPositive().isLessThan(bounded.dropped());

        // what is kept aside is no longer owned; two more owned ones evicted are past the throttle
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        bounded.feedWriteIfNewer("later 1", LIVE, 1, FRESH, now(), status("live"));
        evictOnly(bounded, "later 1");
        bounded.feedWriteIfNewer("later 2", LIVE, 1, FRESH, now(), status("live"));
        evictOnly(bounded, "later 2");
        assertThat(bounded.prunes()).as("pruned again").isGreaterThan(prunes);
        assertThat(requireNonNull(bounded.get("later 2")).get(STATUS))
                .as("what the feed no longer owned made room for it")
                .isEqualTo("live");
    }

    @Test
    void concurrentMessagesForOneEntityLeaveTheNewestValue() throws Exception {
        for (int round = 0; round < 20; round++) {
            String entity = "m" + round;
            List<Future<Boolean>> writes = new ArrayList<>();
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (long timestamp = 1; timestamp <= 50; timestamp++) {
                    long at = timestamp;
                    writes.add(threads.submit(() -> live.feedWriteIfNewer(
                            entity, LIVE, at, FRESH, now(), LiveWrite.of().put(TIMESTAMP, at))));
                }
                for (Future<Boolean> write : writes) {
                    write.get(5, TimeUnit.SECONDS);
                }
            }
            assertThat(timestampOf(entity)).as("the newest message's value").isEqualTo(50);
        }
    }

    private void evict(LiveState<String> bounded, String entity) {
        evictOnly(bounded, entity);
        assertThat(bounded.keptAside()).isEqualTo(1);
    }

    /** Evicts it with others that only REST wrote: evicted, they are not kept aside themselves. */
    private void evictOnly(LiveState<String> bounded, String entity) {
        for (int i = 0; i < 10_000 && bounded.holds(entity); i++) {
            bounded.restWriteIfQuiet(entity + " other " + i, now(), status("not started"));
        }
        assertThat(bounded.holds(entity)).as("evicted for room").isFalse();
    }

    /** A live message carrying its own timestamp as a value, to tell which one wrote last. */
    private boolean feedWrite(String key, long producer, long timestamp, Duration age) {
        return live.feedWriteIfNewer(
                key, producer, timestamp, age, now(), LiveWrite.of().put(TIMESTAMP, timestamp));
    }

    private boolean restWrite(String key) {
        return live.restWriteIfQuiet(key, now(), status("from REST"));
    }

    private static LiveWrite status(String status) {
        return LiveWrite.of().put(STATUS, status);
    }

    private @Nullable String statusOf(String key) {
        return requireNonNull(live.get(key)).get(STATUS);
    }

    private static @Nullable String statusOf(LiveState<String> state, String key) {
        return requireNonNull(state.get(key)).get(STATUS);
    }

    private long timestampOf(String key) {
        return requireNonNull(requireNonNull(live.get(key)).get(TIMESTAMP));
    }

    private Instant now() {
        return time.instant();
    }
}
