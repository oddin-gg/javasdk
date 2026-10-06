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
    void restFillsWhatTheFeedHasNotSentAndTakesNothingOver() {
        assertThat(live.restFill("m1", LiveWrite.of().put(CLOCK, "from REST"), () -> false))
                .as("no record: the next read loads the summary")
                .isFalse();
        assertThat(live.get("m1")).isNull();

        live.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, now(), status("live"));
        time.advance(LiveState.STATUS_AGE.minusMinutes(1));
        assertThat(live.restFill("m1", LiveWrite.of().put(CLOCK, "12:00"), () -> true))
                .as("from an abandoned fetch")
                .isFalse();
        assertThat(live.restFill(
                        "m1",
                        LiveWrite.of().put(STATUS, "ended").put(CLOCK, "12:00").clear(STATUS),
                        () -> false))
                .isTrue();
        var values = requireNonNull(live.get("m1"));
        assertThat(values.get(CLOCK)).as("none yet: filled").isEqualTo("12:00");
        assertThat(values.get(STATUS))
                .as("the feed's: neither replaced nor cleared")
                .isEqualTo("live");
        assertThat(live.restFill("m1", LiveWrite.of().put(CLOCK, "13:00"), () -> false))
                .as("nothing left to fill")
                .isFalse();

        time.advance(Duration.ofMinutes(2));
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("a fill is no REST write: quiet since the feed's last message")
                .isFalse();
        assertThat(feedWrite("m1", LIVE, 999, FRESH))
                .as("nor did it move the watermark")
                .isFalse();
        assertThat(live.restWriteIfQuiet("m1", now(), status("ended"))).isTrue();
        assertThat(statusOf("m1")).isEqualTo("ended");
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
    void aReplayedMessageWritesInTheOrderItComesAndTheWatermarkKeepsTheNewest() {
        live.feedWriteReplayed("m1", LIVE, 2_000, now(), status("ended"));
        live.feedWriteReplayed("m1", LIVE, 1_000, now(), status("live"));
        assertThat(statusOf("m1")).as("the same match played again").isEqualTo("live");
        assertThat(live.feedWriteIfNewer("m1", LIVE, 1_500, FRESH, now(), status("ended")))
                .as("a message in order, older than the newest that wrote")
                .isFalse();
        assertThat(live.feedWriteIfNewer("m1", LIVE, 2_000, FRESH, now(), status("ended")))
                .isTrue();
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
        assertThat(bounded.holds("m0")).as("the least recently written went").isFalse();
        assertThat(bounded.holds("m99")).isTrue();
        assertThat(bounded.dropped()).as("the feed owned none of them").isZero();

        // by its last write, not its first
        bounded.restWriteIfQuiet("m90", now(), status("not started"));
        bounded.restWriteIfQuiet("m100", now(), status("not started"));
        assertThat(bounded.holds("m90")).as("written again, the newest").isTrue();
        assertThat(bounded.holds("m91")).as("the least recently written now").isFalse();
    }

    @Test
    void anEntityTheFeedOwnsStaysWhileOthersMakeRoom() {
        var bounded = new LiveState<String>(10, time);
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        for (int i = 0; i < 1_000; i++) {
            bounded.restWriteIfQuiet("other " + i, now(), status("not started"));
        }
        assertThat(bounded.size()).isEqualTo(10);
        assertThat(statusOf(bounded, "live")).as("the oldest, but the feed's").isEqualTo("live");
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), status("older")))
                .as("an older message from the producer still does not write")
                .isFalse();
        assertThat(bounded.restWriteIfQuiet("live", now(), status("not started")))
                .as("the feed wrote it a moment ago")
                .isFalse();

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        // it joins the others as the newest when the feed stops owning it, so the nine older go first
        for (int i = 0; i < 9; i++) {
            bounded.restWriteIfQuiet("later " + i, now(), status("not started"));
        }
        assertThat(bounded.holds("live")).isTrue();
        for (int i = 9; i < 20; i++) {
            bounded.restWriteIfQuiet("later " + i, now(), status("not started"));
        }
        assertThat(bounded.holds("live"))
                .as("no longer the feed's, it made room")
                .isFalse();
        assertThat(bounded.dropped()).isZero();
    }

    @Test
    void entitiesTheFeedOwnsGoPastTheBoundToTwiceItAndThenTheOldestAreDroppedAndCounted() {
        var bounded = new LiveState<String>(16, time);
        for (int i = 0; i < 64; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.size()).as("twice the bound").isEqualTo(32);
        assertThat(bounded.dropped()).as("what did not fit, counted").isEqualTo(32);
        assertThat(bounded.holds("live 0")).as("the oldest dropped").isFalse();
        assertThat(bounded.holds("live 63")).isTrue();

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        bounded.restWriteIfQuiet("later", now(), status("not started"));
        assertThat(bounded.size())
                .as("what the feed no longer owns makes room down to the bound")
                .isEqualTo(16);
        assertThat(bounded.holds("later")).isTrue();
        assertThat(bounded.dropped()).isEqualTo(32);
    }

    @Test
    void theRecordJustWrittenIsNeverTheOneThatMakesRoom() {
        var bounded = new LiveState<String>(10, time);
        for (int i = 0; i < 10; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.restWriteIfQuiet("new", now(), status("not started")))
                .isTrue();
        assertThat(bounded.holds("new"))
                .as("over the bound only by those the feed owns")
                .isTrue();
        assertThat(bounded.size()).isEqualTo(11);
    }

    @Test
    void othersMakeRoomBeforeAnyTheFeedOwnsHoweverManyItOwns() {
        var bounded = new LiveState<String>(40, time);
        for (int i = 0; i < 40; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        bounded.restWriteIfQuiet("quiet", now(), status("not started"));
        for (int i = 40; i < 80; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.holds("quiet"))
                .as("behind forty the feed owns, it went first")
                .isFalse();
        assertThat(bounded.dropped()).as("none the feed owns").isZero();
        assertThat(bounded.size()).isEqualTo(80);
    }

    @Test
    void aWriteThatThrowsChangesNothing() {
        live.feedWriteIfNewer("m1", LIVE, 5_000, FRESH, now(), status("live"));
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThatThrownBy(() -> live.restWriteIfQuiet("m1", now(), status("ended"), () -> {
                    throw new IllegalStateException("the loader failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statusOf("m1")).isEqualTo("live");
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH))
                .as("its watermark is still there")
                .isFalse();
        assertThat(live.restWriteIfQuiet("m1", now(), status("ended")))
                .as("and the lock was let go of")
                .isTrue();
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
