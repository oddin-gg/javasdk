package com.oddin.oddsfeedsdk.internal.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ThrottleTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void theFirstFailureIsLoggedAndThenOneInAThousand() {
        var throttle = new Throttle();
        List<Long> logged = IntStream.rangeClosed(1, 2_500)
                .mapToObj(i -> throttle.count())
                .filter(Throttle::due)
                .toList();
        assertThat(logged).containsExactly(1L, 1_000L, 2_000L);
    }

    @Test
    void theCountIsOfThisThrottleOnly() {
        var first = new Throttle();
        var second = new Throttle();
        first.count();
        first.count();
        assertThat(second.count()).isEqualTo(1);
        assertThat(first.count()).isEqualTo(3);
    }

    @Test
    void countsDoNotLoseAnyFailureAcrossThreads() throws Exception {
        var throttle = new Throttle();
        int threads = 8;
        int each = 1_000;
        List<Long> logged;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<List<Long>>> work = IntStream.range(0, threads)
                    .mapToObj(t -> (Callable<List<Long>>) () -> IntStream.range(0, each)
                            .mapToObj(i -> throttle.count())
                            .filter(Throttle::due)
                            .toList())
                    .toList();
            logged = pool.invokeAll(work).stream()
                    .flatMap(future -> {
                        try {
                            return future.get().stream();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
        // 8000 failures counted once each: 1, then 1000, 2000, ... 8000
        assertThat(logged).hasSize(9);
        assertThat(throttle.count()).isEqualTo(threads * each + 1);
    }

    @Test
    void aTimeBasedThrottleLogsTheFirstThenAtMostOncePerInterval() {
        var throttle = new Throttle();
        long start = 5_000_000_000L;
        assertThat(throttle.dueEvery(start, MINUTE)).isTrue();
        assertThat(throttle.dueEvery(start + Duration.ofSeconds(5).toNanos(), MINUTE))
                .as("5 s later, within the minute")
                .isFalse();
        assertThat(throttle.dueEvery(start + Duration.ofSeconds(59).toNanos(), MINUTE))
                .as("59 s later")
                .isFalse();
        assertThat(throttle.dueEvery(start + MINUTE.toNanos(), MINUTE))
                .as("a minute later")
                .isTrue();
        assertThat(throttle.dueEvery(start + MINUTE.toNanos() + 1, MINUTE)).isFalse();
    }

    @Test
    void theTimeBasedFormKeepsItsOwnClock() {
        var first = new Throttle();
        var second = new Throttle();
        assertThat(first.dueEvery(0, MINUTE)).isTrue();
        assertThat(second.dueEvery(0, MINUTE)).isTrue();
        assertThat(first.dueEvery(1, MINUTE)).isFalse();
    }
}
