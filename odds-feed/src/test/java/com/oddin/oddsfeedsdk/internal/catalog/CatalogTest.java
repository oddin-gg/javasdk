package com.oddin.oddsfeedsdk.internal.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Refresh after write, stale serving with no age limit, the backoff of failed fetches, early
 * refreshes for missing items, clears and bounds, on a catalog of strings. Fetches run on the
 * reader's thread and background refreshes wait in a queue until the test runs them, unless a test
 * says otherwise.
 */
class CatalogTest {

    private static final Duration REFRESH_AGE = Duration.ofHours(1);
    private static final Duration TICK = Duration.ofNanos(1);

    private final FakeTime time = new FakeTime();
    private final List<Runnable> queuedRefreshes = new ArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Supplier<String> answer = () -> "v1";
    private Catalog<String, String> catalog = catalog(10, Runnable::run, queuedRefreshes::add);

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void aReadFetchesWhatIsNotHeldAndServesItUntilTheRefreshAge() {
        assertThat(catalog.get("k")).isEqualTo("v1");
        answer = () -> "v2";
        time.advance(REFRESH_AGE);
        assertThat(catalog.get("k")).isEqualTo("v1");
        assertThat(fetches).hasValue(1);
        assertThat(queuedRefreshes).as("not stale yet").isEmpty();
        assertThat(catalog.health().servedStale()).isZero();
    }

    @Test
    void aValuePastTheRefreshAgeIsServedAtOnceAndRefreshedInTheBackground() {
        catalog.get("k");
        answer = () -> "v2";
        time.advance(REFRESH_AGE.plus(TICK));

        assertThat(catalog.get("k")).isEqualTo("v1");
        assertThat(catalog.get("k")).isEqualTo("v1");
        assertThat(fetches).as("the reads did not wait for a fetch").hasValue(1);
        assertThat(queuedRefreshes).as("one refresh, however many reads").hasSize(1);
        time.advance(Duration.ofSeconds(5));
        assertThat(catalog.health().servedStale()).isEqualTo(2);
        assertThat(catalog.health().staleFor()).isEqualTo(Duration.ofSeconds(5));

        runRefreshes();
        assertThat(fetches).hasValue(2);
        assertThat(catalog.get("k")).isEqualTo("v2");
        assertThat(catalog.health().staleFor()).as("fresh again").isZero();
        assertThat(queuedRefreshes).isEmpty();
    }

    @Test
    void aStaleValueIsServedWhileTheRefreshRuns() throws Exception {
        var refreshed = new Semaphore(0);
        Executor refreshes = task -> threads.execute(() -> {
            task.run();
            refreshed.release();
        });
        catalog = catalog(10, threads, refreshes);
        catalog.get("k");
        var release = new CountDownLatch(1);
        answer = () -> {
            await(release);
            return "v2";
        };
        time.advance(REFRESH_AGE.plus(TICK));

        Future<String> read = threads.submit(() -> catalog.get("k"));
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
        assertThat(threads.submit(() -> catalog.get("k")).get(5, TimeUnit.SECONDS))
                .as("still the refresh runs")
                .isEqualTo("v1");
        release.countDown();
        assertThat(refreshed.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        assertThat(catalog.get("k")).isEqualTo("v2");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void concurrentReadsOfAKeyNotHeldShareOneFetch() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var release = new CountDownLatch(1);
        answer = () -> {
            await(release);
            return "v1";
        };
        List<Future<String>> reads = new ArrayList<>();
        var readers = new ConcurrentLinkedQueue<Thread>();
        for (int i = 0; i < 20; i++) {
            reads.add(threads.submit(() -> {
                readers.add(Thread.currentThread());
                return catalog.get("k");
            }));
        }
        // every reader waits for the one fetch before it is let go: one that came after it ended
        // would find nothing held yet and start another
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (readers.size() < 20
                || !readers.stream().allMatch(reader -> reader.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime() - until).as("readers waiting").isNegative();
            Thread.onSpinWait();
        }
        release.countDown();
        for (Future<String> read : reads) {
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("v1");
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aFailingRefreshLeavesTheStaleValueServedAndBacksOff() {
        catalog.get("k");
        answer = () -> {
            throw new ApiException("down");
        };
        time.advance(Duration.ofHours(2));
        assertThat(catalog.get("k")).isEqualTo("v1");
        runRefreshes();
        assertThat(catalog.health().failedFetches()).isEqualTo(1);
        assertThat(catalog.health().failing()).isEqualTo(1);

        assertThat(catalog.get("k")).as("served while it fails").isEqualTo("v1");
        assertThat(queuedRefreshes).as("backing off for a second").isEmpty();
        time.advance(Catalog.FIRST_BACKOFF.minus(TICK));
        catalog.get("k");
        assertThat(queuedRefreshes).as("not quite a second").isEmpty();
        time.advance(TICK);
        catalog.get("k");
        assertThat(queuedRefreshes).hasSize(1);
        runRefreshes();

        time.advance(Catalog.FIRST_BACKOFF.multipliedBy(2).minus(TICK));
        catalog.get("k");
        assertThat(queuedRefreshes)
                .as("not quite two seconds after the second failure")
                .isEmpty();
        time.advance(TICK);
        catalog.get("k");
        assertThat(queuedRefreshes).hasSize(1);
        assertThat(fetches).hasValue(3);
        assertThat(catalog.health().staleFor()).as("since first served stale").isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void aValueIsServedForAsLongAsRefreshesFailHoweverOldItGets() {
        catalog.get("k");
        answer = () -> {
            throw new ApiException("down");
        };
        time.advance(REFRESH_AGE.plus(TICK));
        assertThat(catalog.get("k")).isEqualTo("v1");
        runRefreshes();
        for (int day = 1; day <= 30; day++) {
            time.advance(Duration.ofDays(1));
            assertThat(catalog.get("k")).as("day %d of the outage", day).isEqualTo("v1");
            runRefreshes();
        }
        assertThat(fetches).as("a refresh a day, each failed").hasValue(32);
        CatalogHealth health = catalog.health();
        assertThat(health.staleFor()).isEqualTo(Duration.ofDays(30));
        assertThat(health.failing()).isEqualTo(1);
        assertThat(health.servedStale()).isEqualTo(31);
        assertThat(catalog.peekAll()).containsEntry("k", "v1");
    }

    @Test
    void aReadWithNothingToServeFailsAtOnceWhileTheKeyBacksOff() {
        var down = new ApiException("down");
        answer = () -> {
            throw down;
        };
        assertThatThrownBy(() -> catalog.get("k")).isSameAs(down);
        assertThatThrownBy(() -> catalog.get("k"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not fetched again before")
                .hasCause(down);
        assertThat(fetches).hasValue(1);

        time.advance(Catalog.FIRST_BACKOFF.plus(TICK));
        assertThatThrownBy(() -> catalog.get("k")).isSameAs(down);
        assertThat(fetches).hasValue(2);
        time.advance(Catalog.FIRST_BACKOFF.plus(TICK));
        assertThatThrownBy(() -> catalog.get("k")).hasCause(down);
        assertThat(fetches).as("two seconds after the second failure").hasValue(2);
        time.advance(Catalog.FIRST_BACKOFF);
        assertThatThrownBy(() -> catalog.get("k")).isSameAs(down);
        assertThat(fetches).hasValue(3);
        assertThatThrownBy(() -> catalog.get("other"))
                .as("another key does not back off")
                .isSameAs(down);
        assertThat(fetches).hasValue(4);
    }

    @Test
    void aFailureNobodyTriesAgainForTheRefreshAgeIsForgotten() {
        answer = () -> {
            throw new ApiException("down");
        };
        assertThatThrownBy(() -> catalog.get("k")).hasMessage("down");
        time.advance(REFRESH_AGE.minus(TICK));
        assertThat(catalog.health().failing()).isEqualTo(1);
        time.advance(TICK);
        assertThat(catalog.health().failing()).isZero();
    }

    @Test
    void theBackoffDoublesUpToAMinute() {
        answer = () -> {
            throw new ApiException("down");
        };
        long[] seconds = {1, 2, 4, 8, 16, 32, 60, 60, 60};
        assertThatThrownBy(() -> catalog.get("k")).hasMessage("down");
        for (int failure = 0; failure < seconds.length; failure++) {
            Duration backoff = Duration.ofSeconds(seconds[failure]);
            time.advance(backoff.minus(TICK));
            assertThatThrownBy(() -> catalog.get("k")).hasMessageContaining("not fetched again before");
            assertThat(fetches)
                    .as("still backing off after failure %d", failure + 1)
                    .hasValue(failure + 1);
            time.advance(TICK);
            assertThatThrownBy(() -> catalog.get("k")).hasMessage("down");
            assertThat(fetches).as("%s after failure %d", backoff, failure + 1).hasValue(failure + 2);
        }
        assertThat(catalog.health().failedFetches()).isEqualTo(seconds.length + 1);
    }

    @Test
    void aSuccessEndsTheBackoff() {
        answer = () -> {
            throw new ApiException("down");
        };
        for (int failure = 0; failure < 3; failure++) {
            assertThatThrownBy(() -> catalog.get("k")).isInstanceOf(ApiException.class);
            time.advance(Catalog.LONGEST_BACKOFF);
        }
        answer = () -> "v1";
        assertThat(catalog.get("k")).isEqualTo("v1");
        assertThat(catalog.health().failing()).isZero();

        answer = () -> {
            throw new ApiException("down again");
        };
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.get("k");
        runRefreshes();
        time.advance(Catalog.FIRST_BACKOFF.plus(TICK));
        catalog.get("k");
        assertThat(queuedRefreshes)
                .as("a second after the first failure since the success")
                .hasSize(1);
    }

    @Test
    void aReloadFetchesNowWhateverIsHeldAndWhileTheKeyBacksOff() {
        catalog.get("k");
        answer = () -> "v2";
        assertThat(catalog.reload("k")).isEqualTo("v2");
        assertThat(catalog.get("k")).isEqualTo("v2");

        answer = () -> {
            throw new ApiException("down");
        };
        assertThatThrownBy(() -> catalog.reload("k")).hasMessage("down");
        assertThat(catalog.get("k")).as("what was held stays").isEqualTo("v2");
        answer = () -> "v3";
        assertThat(catalog.reload("k")).as("backing off does not stop a reload").isEqualTo("v3");
        assertThat(fetches).hasValue(4);
    }

    @Test
    void anItemMissingFromAValueRefetchesItOnceWhenTheValueIsAMinuteOld() {
        answer = () -> "a,b";
        assertThat(find("c")).as("the value is too young to fetch again").isNull();
        assertThat(fetches).hasValue(1);

        answer = () -> "a,b,c";
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        assertThat(find("c")).as("new upstream").isEqualTo("c");
        assertThat(fetches).hasValue(2);

        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        assertThat(find("d")).isNull();
        assertThat(fetches).as("refetched for d").hasValue(3);
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        assertThat(find("d")).isNull();
        assertThat(fetches)
                .as("d is missing from a value fetched after it was first missed")
                .hasValue(3);

        assertThat(find("e")).isNull();
        assertThat(fetches).hasValue(4);
        assertThat(find("e")).isNull();
        assertThat(fetches).hasValue(4);
    }

    @Test
    void anItemMissingWhileTheKeyBacksOffFetchesNothing() {
        answer = () -> "a,b";
        catalog.get("k");
        answer = () -> {
            throw new ApiException("down");
        };
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        assertThat(find("c")).as("the failed refetch leaves it unknown").isNull();
        assertThat(fetches).hasValue(2);
        assertThat(find("d")).isNull();
        assertThat(fetches).as("backing off").hasValue(2);
    }

    @Test
    void concurrentReadsOfAMissingItemShareOneFetchOfTheValue() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        answer = () -> "a,b";
        catalog.get("k");
        var release = new CountDownLatch(1);
        answer = () -> {
            await(release);
            return "a,b,c";
        };
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        List<Future<@Nullable String>> reads = new ArrayList<>();
        var readers = new ConcurrentLinkedQueue<Thread>();
        for (int i = 0; i < 20; i++) {
            reads.add(threads.submit(() -> {
                readers.add(Thread.currentThread());
                return find("c");
            }));
        }
        // every reader waits for the one fetch before it is let go
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (readers.size() < 20
                || !readers.stream().allMatch(reader -> reader.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime() - until).as("readers waiting").isNegative();
            Thread.onSpinWait();
        }
        release.countDown();
        for (Future<@Nullable String> read : reads) {
            assertThat(read.get(5, TimeUnit.SECONDS))
                    .as("found by the read that missed it")
                    .isEqualTo("c");
        }
        assertThat(fetches).hasValue(2);
    }

    @Test
    void aColdReadThatComesLateToAFailedFetchStartsNoOtherWhileTheKeyBacksOff() {
        var down = new ApiException("down");
        answer = () -> {
            throw down;
        };
        // before it asks the loader, another read's fetch fails and leaves it
        catalog.insideColdRead = () -> {
            catalog.insideColdRead = () -> {};
            assertThatThrownBy(() -> catalog.get("k")).isSameAs(down);
        };
        assertThatThrownBy(() -> catalog.get("k"))
                .hasMessageContaining("not fetched again before")
                .hasCause(down);
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aReadAfterAClearDoesNotJoinAFetchFromBeforeIt() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            return "before the clear";
        };
        Future<String> before = threads.submit(() -> catalog.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear();
        answer = () -> "after the clear";
        assertThat(threads.submit(() -> catalog.get("k")).get(5, TimeUnit.SECONDS))
                .as("its own fetch, while the old one still runs")
                .isEqualTo("after the clear");
        release.countDown();
        assertThat(before.get(5, TimeUnit.SECONDS)).isEqualTo("before the clear");
        assertThat(catalog.peek("k")).isEqualTo("after the clear");
    }

    @Test
    void duringAnOutageAMissingItemDoesNotWaitEachTimeTheBackoffEnds() {
        answer = () -> "a,b";
        catalog.get("k");
        answer = () -> {
            throw new ApiException("down");
        };
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        assertThat(find("c")).as("the first read pays, the API looked well").isNull();
        assertThat(fetches).hasValue(2);
        for (int cycle = 0; cycle < 5; cycle++) {
            time.advance(Catalog.LONGEST_BACKOFF.plus(TICK));
            assertThat(find("c")).isNull();
            assertThat(find("d")).isNull();
            assertThat(fetches).as("no read waited, cycle %d", cycle).hasValue(2 + cycle);
            assertThat(queuedRefreshes).as("one refresh in the background").hasSize(1);
            runRefreshes();
        }
        answer = () -> "a,b,c";
        time.advance(Catalog.LONGEST_BACKOFF.plus(TICK));
        assertThat(find("c")).isNull();
        runRefreshes();
        assertThat(find("c")).as("back, found by a later read").isEqualTo("c");
        assertThat(catalog.health().failing()).isZero();
    }

    @Test
    void aMissingItemIsFoundInWhatAFetchWroteSinceTheReadLooked() {
        answer = () -> "a,b";
        assertThat(find("c")).as("missed while the value is young").isNull();
        time.advance(Catalog.MISS_INTERVAL.plus(TICK));
        answer = () -> "a,b,c";
        catalog.insideMiss = () -> {
            catalog.insideMiss = () -> {};
            catalog.reload("k");
        };
        assertThat(find("c")).isEqualTo("c");
        assertThat(fetches).as("the reload's fetch, no other").hasValue(2);
    }

    @Test
    void anOlderFetchThatEndsAfterANewerOneWritesNothingAndRecordsNoFailure() throws Exception {
        var refreshed = new Semaphore(0);
        catalog = catalog(
                10,
                threads,
                task -> threads.execute(() -> {
                    task.run();
                    refreshed.release();
                }));
        catalog.get("k");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            throw new ApiException("down");
        };
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.get("k");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        answer = () -> "new";
        assertThat(catalog.reload("k")).isEqualTo("new");
        release.countDown();
        assertThat(refreshed.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        assertThat(catalog.peek("k")).isEqualTo("new");
        assertThat(catalog.health().failing()).as("the newer fetch did well").isZero();
    }

    @Test
    void anOlderFetchThatSucceedsAfterANewerOneDoesNotReplaceItsValueOrClearItsFailure() throws Exception {
        var refreshed = new Semaphore(0);
        catalog = catalog(
                10,
                threads,
                task -> threads.execute(() -> {
                    task.run();
                    refreshed.release();
                }));
        catalog.get("k");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            return "old";
        };
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.get("k");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        answer = () -> "new";
        assertThat(catalog.reload("k")).isEqualTo("new");
        answer = () -> {
            throw new ApiException("down");
        };
        assertThatThrownBy(() -> catalog.reload("k")).hasMessage("down");
        release.countDown();
        assertThat(refreshed.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        assertThat(catalog.peek("k")).as("the newer fetch wins").isEqualTo("new");
        assertThat(catalog.health().failing()).as("the newest fetch failed").isEqualTo(1);
    }

    @Test
    void aClearOfOneKeyLeavesTheFetchOfAnotherToWriteItsValue() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            return "b1";
        };
        Future<String> read = threads.submit(() -> catalog.get("b"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear(key -> key.equals("a"));
        release.countDown();
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("b1");
        assertThat(catalog.peek("b")).as("written").isEqualTo("b1");
    }

    @Test
    void aClearOfOneKeyLeavesTheFetchOfAnotherToRecordItsFailure() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var down = new ApiException("down");
        answer = () -> {
            started.countDown();
            await(release);
            throw down;
        };
        Future<String> read = threads.submit(() -> catalog.get("b"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear(key -> key.equals("a"));
        release.countDown();
        assertThatThrownBy(() -> read.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("down");
        assertThatThrownBy(() -> catalog.get("b"))
                .as("backing off, no other fetch")
                .hasMessageContaining("not fetched again before")
                .hasCause(down);
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aClearOfAKeyBeingFetchedForTheFirstTimeStopsThatFetchFromWriting() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            return "before the clear";
        };
        Future<String> read = threads.submit(() -> catalog.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear(key -> key.equals("k"));
        release.countDown();
        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("before the clear");
        assertThat(catalog.peek("k"))
                .as("nothing was held, yet the clear found the fetch")
                .isNull();
    }

    @Test
    void aStaleValueNobodyReadsForTheRefreshAgeIsNotReportedStale() {
        catalog.get("k");
        answer = () -> {
            throw new ApiException("down");
        };
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.get("k");
        runRefreshes();
        time.advance(REFRESH_AGE);
        assertThat(catalog.health().staleFor()).as("read a refresh age ago").isEqualTo(REFRESH_AGE);
        time.advance(TICK);
        assertThat(catalog.health().staleFor()).as("no longer served").isZero();
        catalog.get("k");
        assertThat(catalog.health().staleFor()).as("served again").isEqualTo(REFRESH_AGE.plus(TICK));
    }

    @Test
    void anOlderFetchThatFailsAfterANewerOneFailedLeavesTheNewerFailure() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var older = new ApiException("older down");
        var newer = new ApiException("newer down");
        answer = () -> {
            started.countDown();
            await(release);
            throw older;
        };
        Future<String> read = threads.submit(() -> catalog.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        answer = () -> {
            throw newer;
        };
        assertThatThrownBy(() -> catalog.reload("k")).isSameAs(newer);
        release.countDown();
        assertThatThrownBy(() -> read.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("older down");
        assertThatThrownBy(() -> catalog.get("k"))
                .hasMessageContaining("1 fetches in a row failed")
                .hasCause(newer);
    }

    @Test
    void aClearDropsWhatIsHeldAndAFetchUnderWayWritesNothing() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            return "before the clear";
        };
        Future<String> read = threads.submit(() -> catalog.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear();
        release.countDown();
        assertThat(read.get(5, TimeUnit.SECONDS)).as("its reader has it").isEqualTo("before the clear");
        assertThat(catalog.peek("k")).as("but it was not written").isNull();

        answer = () -> "after the clear";
        assertThat(catalog.get("k")).isEqualTo("after the clear");
        catalog.clear(key -> key.equals("other"));
        assertThat(catalog.peek("k")).as("a clear of another key").isEqualTo("after the clear");
        catalog.clear(key -> key.equals("k"));
        assertThat(catalog.peek("k")).isNull();
    }

    @Test
    void aValueReplacedWhileAReadMarksItStaleIsNotReportedStale() {
        catalog.get("k");
        answer = () -> "v2";
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.insideStaleRead = () -> catalog.reload("k");
        assertThat(catalog.get("k")).isEqualTo("v1");
        catalog.insideStaleRead = () -> {};
        time.advance(Duration.ofMinutes(5));
        assertThat(catalog.health().staleFor()).as("v2 is fresh").isZero();
        assertThat(catalog.get("k")).isEqualTo("v2");
    }

    @Test
    void aValueClearedWhileAReadMarksItStaleIsNotReportedStale() {
        catalog.get("k");
        time.advance(REFRESH_AGE.plus(TICK));
        catalog.insideStaleRead = catalog::clear;
        assertThat(catalog.get("k")).isEqualTo("v1");
        catalog.insideStaleRead = () -> {};
        time.advance(Duration.ofMinutes(5));
        assertThat(catalog.health().staleFor()).as("nothing is held").isZero();
    }

    @Test
    void aClearEndsTheBackoffOfTheKeysItClears() {
        answer = () -> {
            throw new ApiException("down");
        };
        assertThatThrownBy(() -> catalog.get("k")).hasMessage("down");
        assertThatThrownBy(() -> catalog.get("other")).hasMessage("down");
        catalog.clear(key -> key.equals("k"));
        assertThat(catalog.health().failing()).isEqualTo(1);
        answer = () -> "v1";
        assertThat(catalog.get("k")).as("fetched at once").isEqualTo("v1");
        assertThatThrownBy(() -> catalog.get("other"))
                .as("still backing off")
                .hasMessageContaining("not fetched again before");
        assertThat(fetches).hasValue(3);
    }

    @Test
    void aFetchFromBeforeAClearThatFailsAfterItBacksNothingOff() throws Exception {
        catalog = catalog(10, threads, queuedRefreshes::add);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = () -> {
            started.countDown();
            await(release);
            throw new ApiException("down");
        };
        Future<String> read = threads.submit(() -> catalog.get("k"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        catalog.clear();
        release.countDown();
        assertThatThrownBy(() -> read.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("down");
        assertThat(catalog.health().failing()).isZero();
        answer = () -> "v1";
        assertThat(catalog.get("k")).as("fetched at once").isEqualTo("v1");
    }

    @Test
    void theSizeBoundDropsTheValuesOverItAndCountsThem() {
        catalog = catalog(3, Runnable::run, queuedRefreshes::add);
        for (int i = 0; i < 10; i++) {
            catalog.get("k" + i);
        }
        assertThat(catalog.peekAll()).hasSizeLessThanOrEqualTo(3);
        assertThat(catalog.health().evictedForRoom())
                .isEqualTo(10 - catalog.peekAll().size());
    }

    private @Nullable String find(String item) {
        return catalog.find(
                "k", item, (value, wanted) -> List.of(value.split(",")).contains(wanted) ? wanted : null);
    }

    private Catalog<String, String> catalog(long size, Executor fetchesRunOn, Executor refreshesRunOn) {
        return new Catalog<>(
                "test catalog",
                size,
                REFRESH_AGE,
                (_, _, _) -> {
                    fetches.incrementAndGet();
                    return answer.get();
                },
                Duration.ofSeconds(10),
                fetchesRunOn,
                refreshesRunOn,
                time,
                time);
    }

    private void runRefreshes() {
        var queued = List.copyOf(queuedRefreshes);
        queuedRefreshes.clear();
        queued.forEach(Runnable::run);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
