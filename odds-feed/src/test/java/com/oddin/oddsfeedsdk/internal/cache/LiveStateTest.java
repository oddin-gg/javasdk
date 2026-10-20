package com.oddin.oddsfeedsdk.internal.cache;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.Takeover;
import com.oddin.oddsfeedsdk.internal.clock.SplitClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Who writes the fields the feed owns: the feed while it is live, REST once it is quiet. */
class LiveStateTest {

    private static final long LIVE = 2;
    private static final long PREMATCH = 1;
    private static final Duration FRESH = Duration.ofSeconds(1);
    /** The age of a message a few minutes late: no timely one, so no exception to the watermark's order. */
    private static final Duration STALE = Duration.ofMinutes(5);

    private static final Field<String> STATUS = Field.shared("status");
    private static final Field<Long> TIMESTAMP = Field.shared("timestamp");
    private static final Field<Integer> HOME_SCORE = Field.shared("homeScore");
    private static final Field<String> CLOCK = Field.shared("clock");

    private final FakeTime time = new FakeTime();
    private final LiveState<String> live = new LiveState<>(1_000, time.clock());

    @Test
    void aMatchWhoseSummaryLoadedBeforeKickOffGoesLiveWithTheFeed() {
        assertThat(live.get("m1")).as("nothing loaded yet").isNull();
        assertThat(live.restWriteIfQuiet("m1", status("not started")))
                .as("the summary, before the feed has said anything")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("not started");

        assertThat(live.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, now(), status("live")))
                .as("kick-off, on the feed")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("live");
        assertThat(live.restWriteIfQuiet("m1", status("not started")))
                .as("a summary from before kick-off, answered late")
                .isFalse();
        assertThat(statusOf("m1")).isEqualTo("live");

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThat(live.restWriteIfQuiet("m1", status("ended")))
                .as("the feed has gone quiet")
                .isTrue();
        assertThat(statusOf("m1")).isEqualTo("ended");
    }

    @Test
    void liveValuesAreFreshForTheStatusAgeAfterTheFeedOrRestLastWroteThem() {
        live.restWriteIfQuiet("m1", status("not started"));
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
        assertThat(feedWrite("m1", LIVE, 999, STALE))
                .as("nor did it move the watermark")
                .isFalse();
        assertThat(live.restWriteIfQuiet("m1", status("ended"))).isTrue();
        assertThat(statusOf("m1")).isEqualTo("ended");
    }

    @Test
    void aSummaryFromAnAbandonedFetchDoesNotWrite() {
        live.restWriteIfQuiet("m1", status("ended"), () -> false);
        assertThat(live.restWriteIfQuiet("m1", status("not started"), () -> true))
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
            Future<Boolean> rest = threads.submit(() -> live.restWriteIfQuiet("m1", status("not started"), () -> {
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
        assertThat(live.restWriteIfQuiet("m1", status("not started")))
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
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        live.feedWriteReplayed("m1", LIVE, 1_000, now(), status("live"));
        assertThat(statusOf("m1")).as("the same match played again").isEqualTo("live");
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("the feed owns it again from the second run")
                .isTrue();
        assertThat(live.restWriteIfQuiet("m1", status("not started")))
                .as("so a summary does not take it over")
                .isFalse();
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
        assertThat(feedWrite("m1", LIVE, 900, STALE))
                .as("REST taking over does not forget the watermarks")
                .isFalse();
    }

    /**
     * Two sessions take the lock in another order than they received: A, received at minute 5,
     * writes first; then B, stamped after A and received at minute 0, writes by its newer stamp and
     * the receipt of the last write moves back to minute 0. The feed wrote what it received at
     * minute 5, and is quiet from then on.
     */
    @Test
    void restDoesNotWriteWhileTheLatestReceiptOfTheFeedIsWithinTheStatusAgeWhateverTheLastWriteReceived() {
        assertThat(message("m1", BASE, Duration.ofMinutes(5).toMillis(), FRESH))
                .as("A")
                .isTrue();
        assertThat(message("m1", BASE + 1, 0, FRESH))
                .as("B: newer by its stamp, received before A")
                .isTrue();

        time.advance(Duration.ofMinutes(22));
        assertThat(restWrite("m1"))
                .as("the feed received a message 17 minutes ago, B 22")
                .isFalse();
        assertThat(timestampOf("m1")).as("the feed's value stays").isEqualTo(BASE + 1);

        time.advance(Duration.ofMinutes(4));
        assertThat(restWrite("m1")).as("both over the status age").isTrue();
    }

    @Test
    void liveValuesAreFreshForTheStatusAgeAfterTheLatestReceiptOfTheFeedWhateverTheLastWriteReceived() {
        assertThat(message("m1", BASE, Duration.ofMinutes(5).toMillis(), FRESH))
                .as("A")
                .isTrue();
        assertThat(message("m1", BASE + 1, 0, FRESH))
                .as("B: newer by its stamp, received before A")
                .isTrue();

        time.advance(Duration.ofMinutes(22));
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("the feed received a message 17 minutes ago, B 22")
                .isTrue();

        time.advance(Duration.ofMinutes(4));
        assertThat(requireNonNull(live.get("m1")).isFresh(now()))
                .as("both over the status age")
                .isFalse();
    }

    @Test
    void aRecordOutlivesTheStatusByADayAndIsBounded() {
        feedWrite("m1", LIVE, 5_000, FRESH);
        time.advance(Duration.ofHours(23));
        assertThat(feedWrite("m1", LIVE, 4_000, STALE)).as("still remembered").isFalse();
        time.advance(Duration.ofHours(2));
        assertThat(live.get("m1")).as("forgotten after 24 hours").isNull();
        assertThat(feedWrite("m1", LIVE, 4_000, FRESH)).isTrue();

        var bounded = new LiveState<String>(10, time.clock());
        for (int i = 0; i < 100; i++) {
            bounded.restWriteIfQuiet("m" + i, status("not started"));
        }
        assertThat(bounded.size()).isEqualTo(10);
        assertThat(bounded.holds("m0")).as("the least recently written went").isFalse();
        assertThat(bounded.holds("m99")).isTrue();
        assertThat(bounded.dropped()).as("the feed owned none of them").isZero();

        // by its last write, not its first
        bounded.restWriteIfQuiet("m90", status("not started"));
        bounded.restWriteIfQuiet("m100", status("not started"));
        assertThat(bounded.holds("m90")).as("written again, the newest").isTrue();
        assertThat(bounded.holds("m91")).as("the least recently written now").isFalse();
    }

    @Test
    void anEntityTheFeedOwnsStaysWhileOthersMakeRoom() {
        var bounded = new LiveState<String>(10, time.clock());
        bounded.feedWriteIfNewer("live", LIVE, 5_000, FRESH, now(), status("live"));
        for (int i = 0; i < 1_000; i++) {
            bounded.restWriteIfQuiet("other " + i, status("not started"));
        }
        assertThat(bounded.size()).isEqualTo(10);
        assertThat(statusOf(bounded, "live")).as("the oldest, but the feed's").isEqualTo("live");
        assertThat(bounded.feedWriteIfNewer("live", LIVE, 4_000, FRESH, now(), status("older")))
                .as("an older message from the producer still does not write")
                .isFalse();
        assertThat(bounded.restWriteIfQuiet("live", status("not started")))
                .as("the feed wrote it a moment ago")
                .isFalse();

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        // it joins the others as the newest when the feed stops owning it, so the nine older go first
        for (int i = 0; i < 9; i++) {
            bounded.restWriteIfQuiet("later " + i, status("not started"));
        }
        assertThat(bounded.holds("live")).isTrue();
        for (int i = 9; i < 20; i++) {
            bounded.restWriteIfQuiet("later " + i, status("not started"));
        }
        assertThat(bounded.holds("live"))
                .as("no longer the feed's, it made room")
                .isFalse();
        assertThat(bounded.dropped()).isZero();
    }

    @Test
    void entitiesTheFeedOwnsGoPastTheBoundToTwiceItAndThenTheOldestAreDroppedAndCounted() {
        var bounded = new LiveState<String>(16, time.clock());
        for (int i = 0; i < 64; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.size()).as("twice the bound").isEqualTo(32);
        assertThat(bounded.dropped()).as("what did not fit, counted").isEqualTo(32);
        assertThat(bounded.holds("live 0")).as("the oldest dropped").isFalse();
        assertThat(bounded.holds("live 63")).isTrue();

        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        bounded.restWriteIfQuiet("later", status("not started"));
        assertThat(bounded.size())
                .as("what the feed no longer owns makes room down to the bound")
                .isEqualTo(16);
        assertThat(bounded.holds("later")).isTrue();
        assertThat(bounded.dropped()).isEqualTo(32);
    }

    @Test
    void theRecordJustWrittenIsNeverTheOneThatMakesRoom() {
        var bounded = new LiveState<String>(10, time.clock());
        for (int i = 0; i < 10; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        assertThat(bounded.restWriteIfQuiet("new", status("not started"))).isTrue();
        assertThat(bounded.holds("new"))
                .as("over the bound only by those the feed owns")
                .isTrue();
        assertThat(bounded.size()).isEqualTo(11);
    }

    @Test
    void othersMakeRoomBeforeAnyTheFeedOwnsHoweverManyItOwns() {
        var bounded = new LiveState<String>(40, time.clock());
        for (int i = 0; i < 40; i++) {
            bounded.feedWriteIfNewer("live " + i, LIVE, 1, FRESH, now(), status("live"));
        }
        bounded.restWriteIfQuiet("quiet", status("not started"));
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
        assertThatThrownBy(() -> live.restWriteIfQuiet("m1", status("ended"), () -> {
                    throw new IllegalStateException("the loader failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statusOf("m1")).isEqualTo("live");
        assertThat(feedWrite("m1", LIVE, 4_000, STALE))
                .as("its watermark is still there")
                .isFalse();
        assertThat(live.restWriteIfQuiet("m1", status("ended")))
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
        return live.restWriteIfQuiet(key, status("from REST"));
    }

    @ParameterizedTest
    @ValueSource(longs = {30, -30})
    void aStepOfTheWallClockDoesNotLetRestTakeOverAFeedThatIsStillLive(long minutes) {
        var split = new SplitClock();
        var state = new LiveState<String>(1_000, split.clock());
        state.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, split.clock().monoMillis(), status("live"));

        split.stepWall(Duration.ofMinutes(minutes));
        assertThat(state.restWriteIfQuiet("m1", status("ended")))
                .as("the feed wrote a moment ago by the monotonic clock")
                .isFalse();
        assertThat(statusOf(state, "m1")).isEqualTo("live");
        assertThat(requireNonNull(state.get("m1")).isFresh(split.clock().monoMillis()))
                .as("the feed's status is as fresh as it was")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 0, -30})
    void aFeedQuietByTheMonotonicClockIsTakenOverByRestWhateverTheWallClockSays(long minutes) {
        var split = new SplitClock();
        var state = new LiveState<String>(1_000, split.clock());
        state.feedWriteIfNewer("m1", LIVE, 1_000, FRESH, split.clock().monoMillis(), status("live"));

        split.advanceMono(LiveState.STATUS_AGE.plusMinutes(1));
        split.stepWall(Duration.ofMinutes(minutes));
        assertThat(requireNonNull(state.get("m1")).isFresh(split.clock().monoMillis()))
                .as("the summary is to be loaded again")
                .isFalse();
        assertThat(state.restWriteIfQuiet("m1", status("ended")))
                .as("quiet for the status age")
                .isTrue();
        assertThat(statusOf(state, "m1")).isEqualTo("ended");
    }

    @Test
    void aRecordAgesByTheMonotonicClockWhateverTheWallClockSays() {
        var split = new SplitClock();
        var state = new LiveState<String>(1_000, split.clock());
        state.restWriteIfQuiet("m1", status("ended"));

        split.stepWall(LiveState.AGE.plusHours(1));
        assertThat(state.holds("m1")).as("a wall clock step forward").isTrue();

        split.advanceMono(LiveState.AGE.plusHours(1));
        split.stepWall(LiveState.AGE.plusHours(1).negated());
        assertThat(state.holds("m1")).as("the record's own day is over").isFalse();
    }

    // ---- a watermark no delivery delay explains does not hold the correct messages back

    private static final long BASE = 1_000_000;
    private static final long TEN_MINUTES = Duration.ofMinutes(10).toMillis();
    /** The age of a message stamped ten minutes ahead of its producer: negative. */
    private static final Duration AHEAD = Duration.ofMinutes(-10);

    /** A message with its timestamp as its value, received {@code at} after the test's start. */
    private boolean message(String key, long timestamp, long at, Duration age) {
        return live.feedWriteIfNewer(
                key, LIVE, timestamp, age, start + at, LiveWrite.of().put(TIMESTAMP, timestamp));
    }

    private final long start = now();

    @Test
    void aTimestampAheadOfItsProducerDoesNotHoldBackTheCorrectMessagesThatFollow() {
        assertThat(message("m1", BASE + TEN_MINUTES, 0, FRESH))
                .as("the bad one")
                .isTrue();

        assertThat(message("m1", BASE + 1_000, 1_000, FRESH))
                .as("a correct one a second later")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 1_000);
        assertThat(message("m1", BASE + 2_000, 2_000, FRESH))
                .as("the next, in order")
                .isTrue();
        assertThat(message("m1", BASE + 1_500, 2_500, FRESH))
                .as("a swapped one: the watermark is the correct one's now")
                .isFalse();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 2_000);

        assertThat(message("m1", BASE + TEN_MINUTES + 5_000, 3_000, FRESH))
                .as("another bad one is newer by its stamp")
                .isTrue();
        assertThat(message("m1", BASE + 4_000, 4_000, FRESH))
                .as("and the correct one after it takes over again")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 4_000);
    }

    /** The swaps the rule must keep refusing: every earlier rule died on one of these. */
    @ParameterizedTest
    @CsvSource({
        // swapped by (ms), received after the newer one by (ms), the age by the producer's alives (ms)
        "50, 3, 100",
        "1000, 5, 100",
        "5000, 100, 100",
        "25000, 300, 100",
        // alives 3 s and 25 s late: the corrected age is negative
        "2000, 300, -2700",
        "1000, 300, -24700",
        // R + G is 31 s, but the message is 28 s old: it is a late one
        "28000, 3000, 28000",
    })
    void anOrdinarilySwappedMessageIsStillRefused(long swap, long receivedAfter, long ageMillis) {
        assertThat(message("m1", BASE, 0, FRESH)).isTrue();
        assertThat(message("m1", BASE - swap, receivedAfter, Duration.ofMillis(ageMillis)))
                .isFalse();
        assertThat(timestampOf("m1")).isEqualTo(BASE);
    }

    @Test
    void aMessageThatSatInABacklogDoesNotTakeTheWatermarkOver() {
        assertThat(message("m1", BASE, 0, FRESH)).isTrue();
        Duration timely = Takeover.DEFAULTS.timelyAge();
        assertThat(message("m1", BASE - 40_000, 1_000, timely.plusMillis(1)))
                .as("R + G is 41 s, and it is a millisecond too old to be timely")
                .isFalse();
        assertThat(message("m1", BASE - 40_000, 2_000, timely))
                .as("at the timely age exactly, it is")
                .isTrue();
    }

    /** The stated limit: a producer's alives 35 s late make a backlog message of 45 s look timely. */
    @Test
    void aBacklogMessageOfAProducerWhoseAlivesAreVeryLateTakesTheWatermarkOver() {
        assertThat(message("m1", BASE, 0, FRESH)).isTrue();
        assertThat(message("m1", BASE - 45_000, 100, Duration.ofSeconds(45 - 35)))
                .as("the alives' delay is taken off its age: it reads timely")
                .isTrue();
    }

    @Test
    void aMessageReceivedBeforeTheWatermarksIsNotTheLaterOneWhateverElseIsTrue() {
        assertThat(message("m1", BASE + TEN_MINUTES, 5_000, FRESH)).isTrue();
        assertThat(message("m1", BASE, 4_000, FRESH))
                .as("received before it, handled after it by another session")
                .isFalse();
        assertThat(message("m1", BASE, 5_000, FRESH))
                .as("in the same millisecond")
                .isFalse();
        assertThat(message("m1", BASE, 5_001, FRESH)).as("a millisecond later").isTrue();
    }

    /**
     * Two sessions take the lock in another order than they received: the correct update U,
     * received at 1000, writes first; then the bad F, received at 900, writes by its newer stamp and
     * the watermark's receipt is 900; then the timely C, received at 950, is stamped after U's
     * (so the stamp floor below does not refuse it) but received before it: it must not take the
     * watermark over.
     */
    @Test
    void aMessageReceivedBeforeAnUpdateThatWroteDoesNotTakeOverWhateverTheWatermarksReceiptIs() {
        assertThat(message("m1", BASE + 800, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES, 900, AHEAD))
                .as("F: newer by its stamp, received before U")
                .isTrue();
        assertThat(message("m1", BASE + 850, 950, FRESH))
                .as("C: timely, received before U")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + TEN_MINUTES);
        assertThat(message("m1", BASE + 850, 1_000, FRESH))
                .as("received with U: not after it")
                .isFalse();
        assertThat(message("m1", BASE + 1_100, 1_001, FRESH))
                .as("a correct message received after all of them takes over")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 1_100);
    }

    /**
     * Two sessions take the lock in another order than they received: the correct update U,
     * received at 1000, writes first; then F, stamped ten minutes ahead and received at 900, writes
     * by its newer stamp. A timely C stamped before U, received after U (by a millisecond, or by a
     * moment), passes the receipt and the gap against F, but is older than a write that was
     * correct: it is refused, by the stamp F's jump left U's behind. A message stamped after U
     * takes over.
     */
    @Test
    void aMessageOlderThanTheUpdateBeforeTheJumpIsRefusedWhateverItsReceipt() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES, 900, AHEAD))
                .as("F: newer by its stamp, handled after U, received before it")
                .isTrue();
        assertThat(message("m1", BASE + 950, 1_001, FRESH))
                .as("C: received a millisecond after U, stamped before it")
                .isFalse();
        assertThat(message("m1", BASE + 950, 1_100, FRESH))
                .as("or a moment after")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + TEN_MINUTES);
        assertThat(message("m1", BASE + 1_010, 1_100, FRESH))
                .as("a message stamped after U takes over")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 1_010);
    }

    @Test
    void theStampBeforeTheJumpIsKeptThroughFurtherRaisesAndForgottenWhenAMessageTakesOver() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES, 1_100, AHEAD))
                .as("the jump")
                .isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES + 500, 1_200, AHEAD))
                .as("a small raise")
                .isTrue();
        assertThat(message("m1", BASE + 3 * TEN_MINUTES, 1_300, AHEAD.multipliedBy(3)))
                .as("a second jump, which does not take the bad stamp before it")
                .isTrue();
        assertThat(message("m1", BASE + 950, 1_400, FRESH))
                .as("still below U's")
                .isFalse();
        assertThat(message("m1", BASE + 1_020, 1_500, FRESH))
                .as("C1 takes over")
                .isTrue();

        assertThat(message("m1", BASE + TEN_MINUTES, 1_600, AHEAD))
                .as("a new jump, from C1")
                .isTrue();
        assertThat(message("m1", BASE + 1_010, 1_700, FRESH))
                .as("between U and C1: older than the write the jump left behind")
                .isFalse();
        assertThat(message("m1", BASE + 1_030, 1_800, FRESH)).as("after C1").isTrue();
    }

    /**
     * A stamp ahead jumps, the match goes quiet, and a correct message at the producer's now raises
     * the watermark past it: the stamp the first jump left behind is stale, and the next jump leaves
     * the correct message's. An older correct message that comes after it is refused by that stamp.
     */
    @Test
    void aMessageAtTheProducersNowThatRaisesTheWatermarkReplacesTheStampBeforeAnEarlierJump() {
        long quiet = TEN_MINUTES;
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 1_000 + TEN_MINUTES, 1_100, AHEAD))
                .as("F1: ten minutes ahead, the jump")
                .isTrue();

        assertThat(message("m1", BASE + 1_500 + TEN_MINUTES, 1_100 + quiet, FRESH))
                .as("V: ten quiet minutes on, at the producer's now, a little past F1's stamp")
                .isTrue();
        assertThat(message("m1", BASE + 1_500 + 2 * TEN_MINUTES, 1_200 + quiet, AHEAD))
                .as("F2: ten minutes ahead again, the jump")
                .isTrue();

        assertThat(message("m1", BASE + 1_000 + TEN_MINUTES, 1_300 + quiet, FRESH))
                .as("C: timely, received after all, older than V")
                .isFalse();
        assertThat(timestampOf("m1")).as("F2's value stays").isEqualTo(BASE + 1_500 + 2 * TEN_MINUTES);
        assertThat(message("m1", BASE + 1_600 + TEN_MINUTES, 1_400 + quiet, FRESH))
                .as("a message stamped after V takes over")
                .isTrue();
    }

    /** A late message, then a normal one: a jump by the late stamp, which the normal message is past. */
    @Test
    void aJumpFromALateMessageToATimelyOneLeavesNoStampBehind() {
        assertThat(message("m1", BASE, 0, Duration.ofMinutes(2)))
                .as("A: a late message, two minutes old")
                .isTrue();
        assertThat(message("m1", BASE + 120_000, 1_000, FRESH))
                .as("B: a timely one, the jump from A's stamp")
                .isTrue();
        assertThat(message("m1", BASE + 130_000, 11_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 130_000 + TEN_MINUTES, 11_100, AHEAD))
                .as("F: ten minutes ahead, the jump from U")
                .isTrue();

        assertThat(message("m1", BASE + 129_500, 11_200, FRESH))
                .as("C: timely, received after all, newer than A, older than U")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + 130_000 + TEN_MINUTES);
    }

    /** The bad stamp follows the benign jump directly: the floor is the timely message's, not the late one's. */
    @Test
    void aStampAheadRightAfterAJumpFromALateMessageLeavesTheTimelyMessagesStampBehind() {
        assertThat(message("m1", BASE, 0, Duration.ofMinutes(2)))
                .as("A: a late message, two minutes old")
                .isTrue();
        assertThat(message("m1", BASE + 120_000, 1_000, FRESH))
                .as("B: a timely one, the jump from A's stamp")
                .isTrue();
        assertThat(message("m1", BASE + 120_000 + TEN_MINUTES, 1_100, AHEAD))
                .as("F: ten minutes ahead, the jump from B")
                .isTrue();

        assertThat(message("m1", BASE + 60_000, 1_200, FRESH))
                .as("C: timely, received after all, newer than A, older than B")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + 120_000 + TEN_MINUTES);
    }

    /**
     * Two sessions take the lock in another order than they received: U, received at 50 s, writes;
     * then F, stamped a millisecond after it and received at 0, writes in order. A timely C, stamped
     * before U and received a millisecond after it, is 1 ms behind the latest receipt, not 50 s: it is
     * a swap, not a takeover.
     */
    @Test
    void aMessageIsLateByTheLatestReceiptOfAWriteNotByTheWatermarksOwn() {
        assertThat(message("m1", BASE + 50_000, 50_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 50_001, 0, Duration.ofMillis(-50_001)))
                .as("F: a millisecond newer, received before U, handled after it")
                .isTrue();

        assertThat(message("m1", BASE + 49_990, 50_001, Duration.ofMillis(11)))
                .as("C: timely, received a millisecond after U, 11 ms older than it")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + 50_001);
    }

    /**
     * U, received at 1000, writes; then A, stamped after it and received at 500, writes in order; then
     * F, stamped 30.5 s after A and received 100 ms after U, is a jump: by the latest receipt only 100
     * ms went by, by A's own 600. C, timely, stamped before U, is refused by the stamp A left behind.
     */
    @Test
    void aJumpIsMeasuredFromTheLatestReceiptOfAWriteNotTheWatermarksOwn() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 2_000, 500, Duration.ofMillis(-1_500)))
                .as("A: newer, received before U, handled after it")
                .isTrue();
        assertThat(message("m1", BASE + 32_500, 1_100, Duration.ofMillis(-31_400)))
                .as("F: 30.5 s after A, 100 ms after U: the jump")
                .isTrue();

        assertThat(message("m1", BASE + 950, 1_200, Duration.ofMillis(250)))
                .as("C: timely, received after all, older than U and A")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + 32_500);
    }

    /**
     * A stamp ahead by less than the margin plus the delay of the receipts is no jump, yet it is
     * ahead of the producer's now: a correct message older than the update before it, and timely, is
     * as much a swap as one after a jump, and is refused by that update's stamp.
     */
    @Test
    void aStampAheadByLessThanTheMarginLeavesTheStampBeforeItBehind() {
        assertThat(message("m1", BASE, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 25_000, 1_100, Duration.ofSeconds(-25)))
                .as("F: 25 s ahead, less than the margin and the 100 ms between the receipts")
                .isTrue();

        assertThat(message("m1", BASE - 5_100, 1_200, Duration.ofMillis(5_300)))
                .as("C: timely, received after all, 30.1 s before F, older than U")
                .isFalse();
        assertThat(timestampOf("m1")).as("F's value stays").isEqualTo(BASE + 25_000);
    }

    /**
     * Two stamps ahead, the second a little further, neither a jump: the stamp before the first is
     * the floor, not the first one's, so a correct message after U still takes over.
     */
    @Test
    void aChainOfStampsAheadByLessThanTheMarginKeepsTheStampBeforeTheFirstOne() {
        assertThat(message("m1", BASE, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 20_000, 1_100, Duration.ofSeconds(-20)))
                .as("F1")
                .isTrue();
        assertThat(message("m1", BASE + 25_000, 1_200, Duration.ofSeconds(-24)))
                .as("F2, further ahead")
                .isTrue();

        assertThat(message("m1", BASE - 5_100, 1_300, Duration.ofSeconds(5)))
                .as("C1: timely, received after all, older than U")
                .isFalse();
        assertThat(message("m1", BASE + 1_000, 8_200, Duration.ofSeconds(7)))
                .as("C2: after U, received 7 s after F2: takes over")
                .isTrue();
        assertThat(timestampOf("m1")).as("C2's value").isEqualTo(BASE + 1_000);
    }

    /**
     * A late message between U and a stamp ahead is the watermark the stamp ahead raised, and its
     * stamp is the floor: it neither records nor forgets one itself, being behind the producer's now.
     */
    @Test
    void aLateMessageBetweenAnUpdateAndAStampAheadIsTheStampBeforeIt() {
        assertThat(message("m1", BASE, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 500, 1_100, Duration.ofSeconds(12)))
                .as("L: late, in order")
                .isTrue();
        assertThat(message("m1", BASE + 25_000, 1_200, Duration.ofSeconds(-24)))
                .as("F: ahead of the producer's now")
                .isTrue();

        assertThat(message("m1", BASE + 300, 7_300, Duration.ofSeconds(5)))
                .as("C: timely, received after all, after U, before L")
                .isFalse();
        assertThat(message("m1", BASE + 600, 8_300, Duration.ofSeconds(7)))
                .as("a message after L takes over")
                .isTrue();
    }

    /** Stamps ahead, none of them timely, never replace the stamp before the first jump. */
    @Test
    void aChainOfStampsAheadKeepsTheStampBeforeTheFirstOne() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 1_000 + TEN_MINUTES, 1_100, AHEAD))
                .as("F1")
                .isTrue();
        assertThat(message("m1", BASE + 1_000 + TEN_MINUTES + 20_000, 1_200, Duration.ofSeconds(-620)))
                .as("F2: a little later, still ahead by a minute less than ten")
                .isTrue();
        assertThat(message("m1", BASE + 1_000 + 2 * TEN_MINUTES, 1_300, Duration.ofMinutes(-20)))
                .as("F3: further ahead")
                .isTrue();

        assertThat(message("m1", BASE + 950, 1_400, FRESH))
                .as("C: timely, received after all, older than U")
                .isFalse();
    }

    /** A message at the producer's now, on either side: the timely age exactly, and no more. */
    @Test
    void aMessageIsAtTheProducersNowWithinTheTimelyAgeOnEitherSide() {
        var takeover = Takeover.DEFAULTS;
        var timely = takeover.timelyAge();
        assertThat(takeover.atProducersNow(Duration.ZERO)).isTrue();
        assertThat(takeover.atProducersNow(timely)).isTrue();
        assertThat(takeover.atProducersNow(timely.negated())).isTrue();
        assertThat(takeover.atProducersNow(timely.plusMillis(1))).isFalse();
        assertThat(takeover.atProducersNow(timely.negated().minusMillis(1))).isFalse();
        assertThat(takeover.atProducersNow(Duration.ofSeconds(Long.MIN_VALUE))).isFalse();
        assertThat(takeover.atProducersNow(Duration.ofSeconds(Long.MAX_VALUE))).isFalse();

        assertThat(takeover.ahead(Duration.ZERO)).isFalse();
        assertThat(takeover.ahead(timely)).as("late, not ahead").isFalse();
        assertThat(takeover.ahead(timely.negated()))
                .as("the timely age ahead exactly")
                .isFalse();
        assertThat(takeover.ahead(timely.negated().minusMillis(1))).isTrue();
        assertThat(takeover.ahead(Duration.ofSeconds(Long.MIN_VALUE))).isTrue();
        assertThat(takeover.ahead(Duration.ofSeconds(Long.MAX_VALUE))).isFalse();
    }

    /**
     * A replayed message, and a recovery's answer, carry no corrected age: they say nothing of where
     * the producer's now is, and leave the stamp before the jump where it was.
     */
    @Test
    void aWriteWithNoCorrectedAgeDoesNotReplaceTheStampBeforeTheJump() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 1_000 + TEN_MINUTES, 1_100, AHEAD))
                .as("F, the jump")
                .isTrue();

        assertThat(live.feedWriteIfNewer(
                        "m1",
                        LIVE,
                        BASE + 1_000 + TEN_MINUTES + 500,
                        FRESH,
                        start + 1_200,
                        false,
                        LiveWrite.of().put(TIMESTAMP, 1L)))
                .as("a recovery's answer, in order, young")
                .isTrue();
        live.feedWriteReplayed(
                "m1",
                LIVE,
                BASE + 1_000 + TEN_MINUTES + 1_000,
                start + 1_300,
                LiveWrite.of().put(TIMESTAMP, 2L));

        assertThat(message("m1", BASE + 950, 1_400, FRESH))
                .as("C: timely, received after all, older than U")
                .isFalse();
    }

    /** A quiet match's next message raises the stamp by as much as the quiet lasted: no jump. */
    @Test
    void aMessageAfterAQuietMatchIsNoJumpAndLeavesNoStampBehind() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 301_000, 301_000, FRESH))
                .as("five minutes on, by the stamp and by the receipt")
                .isTrue();
        assertThat(message("m1", BASE + 900_000, 302_000, Duration.ofMinutes(-10)))
                .as("F, the jump")
                .isTrue();
        assertThat(message("m1", BASE + 150_000, 303_000, FRESH))
                .as("older than the quiet match's message, newer than U: below the stamp before the jump")
                .isFalse();
        assertThat(message("m1", BASE + 301_500, 304_000, FRESH)).isTrue();
    }

    /**
     * A bad stamp inside the margin still holds the correct messages back, for as long as the margin
     * says; one that follows it a long way ahead ends that, and the correct message after the update
     * before them both takes over: the stamp inside the margin is not the floor.
     */
    @Test
    void aBadStampInsideTheMarginHoldsBackUntilALaterOneAndIsNotTheFloor() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + 21_000, 1_100, Duration.ofSeconds(-20)))
                .as("F0, 20 s ahead")
                .isTrue();
        assertThat(message("m1", BASE + 2_000, 1_150, FRESH))
                .as("a correct message, below F0's stamp: R + G is 20 s")
                .isFalse();
        assertThat(message("m1", BASE + TEN_MINUTES, 1_200, AHEAD))
                .as("F1, the jump")
                .isTrue();
        assertThat(message("m1", BASE + 2_000, 1_300, FRESH))
                .as("the correct message again: after U, below F0's stamp, takes over")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + 2_000);
    }

    /** The stated limit: two bad stamps in a row, the second older, leave nothing of the first jump's stamp. */
    @Test
    void ofTwoBadStampsTheSecondOlderOneTheStampBeforeTheFirstJumpIsForgotten() {
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH)).as("U").isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES, 1_100, AHEAD)).as("F1").isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES - 60_000, 1_200, FRESH))
                .as("F2, older than F1, received after it: takes over")
                .isTrue();
        assertThat(message("m1", BASE + 950, 1_300, FRESH))
                .as("C, older than U: written - a second fault")
                .isTrue();
    }

    @Test
    void theTimestampsAndTheReceiptsMustAddUpToMoreThanTheMargin() {
        long margin = Takeover.DEFAULTS.margin().toMillis();
        assertThat(message("m1", BASE, 0, FRESH)).isTrue();
        assertThat(message("m1", BASE - 20_000, 10_000, FRESH))
                .as("R + G is the margin exactly")
                .isFalse();
        assertThat(message("m1", BASE - 20_000, 10_001, FRESH))
                .as("a millisecond more")
                .isTrue();
        assertThat(margin).isEqualTo(30_000);
    }

    @Test
    void aTimestampThatIsNotARealOneNeverOverflowsIntoATakeover() {
        assertThat(message("m1", Long.MAX_VALUE, 0, FRESH))
                .as("a bad one first")
                .isTrue();
        assertThat(message("m1", 0, 1_000, FRESH)).as("zero").isFalse();
        assertThat(message("m1", -1, 2_000, FRESH)).as("negative").isFalse();
        assertThat(message("m1", Long.MIN_VALUE, 3_000, FRESH))
                .as("the least a long can be, which its age wraps around for")
                .isFalse();
        assertThat(message("m1", BASE, 4_000, FRESH))
                .as("a correct one, against the greatest a long can be")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE);

        assertThat(message("m2", 1, 0, FRESH)).isTrue();
        assertThat(message("m2", Long.MIN_VALUE, 1_000, FRESH)).isFalse();
        assertThat(timestampOf("m2")).isEqualTo(1);
    }

    @Test
    void ofTwoTimestampsAheadTheOneReceivedLastWritesAndOfTwoOfOneFastComponentTheNewerStays() {
        assertThat(message("m1", BASE + TEN_MINUTES, 0, FRESH)).isTrue();
        assertThat(message("m1", BASE + TEN_MINUTES - 60_000, 100, FRESH))
                .as("a minute older, received later: the receipt order is what is left to go by")
                .isTrue();
        assertThat(timestampOf("m1")).isEqualTo(BASE + TEN_MINUTES - 60_000);

        assertThat(message("m2", BASE + TEN_MINUTES, 0, FRESH)).isTrue();
        assertThat(message("m2", BASE + TEN_MINUTES - 1_000, 5, FRESH))
                .as("swapped by a second: the same fast component")
                .isFalse();
    }

    @Test
    void theExceptionReadsOnlyTheWatermarkOfTheMessagesOwnProducer() {
        assertThat(live.feedWriteIfNewer(
                        "m1",
                        LIVE,
                        BASE + TEN_MINUTES,
                        FRESH,
                        start,
                        LiveWrite.of().put(TIMESTAMP, 1L)))
                .isTrue();
        assertThat(live.feedWriteIfNewer("m1", PREMATCH, BASE + 1_000, FRESH, start + 10, status("a")))
                .isTrue();
        assertThat(live.feedWriteIfNewer("m1", PREMATCH, BASE + 500, FRESH, start + 20_000, status("b")))
                .as("20.5 s against its own producer's watermark, 600 s against the other's")
                .isFalse();
    }

    @Test
    void aWriteByTheExceptionKeepsTheEntityOwnedByTheFeedFromThenOn() {
        assertThat(message("m1", BASE + TEN_MINUTES, 0, FRESH)).isTrue();
        time.advance(Duration.ofMinutes(19));
        assertThat(live.feedWriteIfNewer("m1", LIVE, BASE + 1_000, FRESH, now(), status("live")))
                .as("a correct message, 19 minutes on")
                .isTrue();

        time.advance(Duration.ofMinutes(2));
        assertThat(restWrite("m1"))
                .as("21 minutes after the bad one, 2 after the correct one: still the feed's")
                .isFalse();
        time.advance(Duration.ofMinutes(19));
        assertThat(restWrite("m1"))
                .as("quiet for the status age since the correct one")
                .isTrue();
    }

    @Test
    void aBadTimestampUnderTheMarginStillFreezesTheFeedUntilRestTakesOver() {
        assertThat(message("m1", BASE + 25_000, 0, FRESH)).isTrue();
        assertThat(message("m1", BASE + 1_000, 1_000, FRESH))
                .as("R + G is 25 s, under the margin")
                .isFalse();
        time.advance(LiveState.STATUS_AGE.plusMinutes(1));
        assertThat(restWrite("m1"))
                .as("REST takes it over once the feed is quiet, as before")
                .isTrue();
    }

    @Test
    void aMessageThatMayNotTakeOverIsRefusedWhateverElseIsTrueOfIt() {
        assertThat(message("m1", BASE + TEN_MINUTES, 0, FRESH)).isTrue();
        assertThat(live.feedWriteIfNewer("m1", LIVE, BASE, FRESH, start + 1_000, false, status("recovery")))
                .as("received later, generated long before, timely: and it answers a recovery request")
                .isFalse();
        assertThat(timestampOf("m1")).isEqualTo(BASE + TEN_MINUTES);
        assertThat(live.feedWriteIfNewer("m1", LIVE, BASE + TEN_MINUTES + 1, FRESH, start + 2_000, false, status("r")))
                .as("one that is newer writes as it ever did")
                .isTrue();
        assertThat(message("m1", BASE, 3_000, FRESH))
                .as("the next live message repairs it")
                .isTrue();
    }

    @Test
    void theMarginAndTheTimelyAgeAreTheOnesSet() {
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", "api.invalid")
                .setAccessToken("token")
                .setFutureStampMargin(Duration.ofSeconds(60))
                .setTimelyMessageAge(Duration.ofSeconds(20))
                .build();
        var state = new LiveState<String>(1_000, time.clock(), Takeover.from(configuration));
        state.feedWriteIfNewer("m1", LIVE, BASE, FRESH, start, status("newer"));

        assertThat(state.feedWriteIfNewer("m1", LIVE, BASE - 40_000, FRESH, start + 1_000, status("older")))
                .as("R + G is 41 s: over the default margin, under the 60 s set")
                .isFalse();
        assertThat(state.feedWriteIfNewer(
                        "m1", LIVE, BASE - 70_000, Duration.ofSeconds(20), start + 2_000, status("a")))
                .as("a message of 20 s is timely by the 20 s set, not by the default 10 s")
                .isTrue();
        assertThat(state.feedWriteIfNewer(
                        "m1", LIVE, BASE - 140_000, Duration.ofMillis(20_001), start + 3_000, status("b")))
                .isFalse();
    }

    @Test
    void aTakeoverNeedsAPositiveMarginAndATimelyAgeOfAtMostHalfIt() {
        var second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new Takeover(Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Takeover(second, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Takeover(second.negated(), second.negated()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Takeover(second, second)).isInstanceOf(IllegalArgumentException.class);
        var margin = Duration.ofSeconds(2);
        assertThatThrownBy(() -> new Takeover(margin, second.plusMillis(1)))
                .as("a millisecond over half the margin")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Takeover(margin, second).timelyAge())
                .as("half the margin")
                .isEqualTo(second);
    }

    /**
     * A bad-stamped and a correct message handled by two threads at once, either first: the value is
     * the last one's whichever it is, and never one older than both, and the watermark after it is
     * the value's, so what follows is judged by it.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aBadAndACorrectMessageHandledAtOnceLeaveTheValueOfOneOfThemAndItsWatermark() throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 50; round++) {
                String entity = "m" + round;
                var go = new CountDownLatch(1);
                long bad = BASE + TEN_MINUTES;
                long correct = BASE + 1_000;
                Future<Boolean> badWrite = threads.submit(() -> {
                    go.await();
                    return message(entity, bad, 0, AHEAD);
                });
                Future<Boolean> correctWrite = threads.submit(() -> {
                    go.await();
                    return message(entity, correct, 1_000, FRESH);
                });
                go.countDown();
                assertThat(badWrite.get(5, TimeUnit.SECONDS))
                        .as("newer, or first: it writes")
                        .isTrue();
                assertThat(correctWrite.get(5, TimeUnit.SECONDS))
                        .as("first, or taking over: it writes")
                        .isTrue();

                long value = timestampOf(entity);
                assertThat(value).as("one of the two").isIn(bad, correct);
                assertThat(message(entity, correct - 500, 1_100, FRESH))
                        .as("a message just behind the correct one is refused, whichever lock order")
                        .isFalse();
                assertThat(message(entity, correct + 500, 1_200, FRESH))
                        .as("one just after it writes, by the watermark that is left")
                        .isTrue();
            }
        }
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

    /** The feed's monotonic clock, as a message is stamped with it when it is received. */
    private long now() {
        return time.clock().monoMillis();
    }
}
