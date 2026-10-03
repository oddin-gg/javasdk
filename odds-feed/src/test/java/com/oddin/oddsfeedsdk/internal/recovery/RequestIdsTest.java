package com.oddin.oddsfeedsdk.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** Recovery request ids: a random start, one up each time, a new start at the end of the range. */
class RequestIdsTest {

    @Test
    void idsStartAfterARandomSeedAndGoUpByOne() {
        var ids = new RequestIds(seeds(41));
        assertThat(List.of(ids.next(id -> false), ids.next(id -> false), ids.next(id -> false)))
                .containsExactly(42L, 43L, 44L);
    }

    @Test
    void theEndOfTheRangeTakesANewRandomStart() {
        var ids = new RequestIds(seeds(RequestIds.MAX - 2, 6));
        assertThat(ids.next(id -> false)).isEqualTo(RequestIds.MAX - 1);
        assertThat(ids.next(id -> false)).isEqualTo(RequestIds.MAX);
        assertThat(ids.next(id -> false)).as("reseeded").isEqualTo(7);
    }

    @Test
    void theFirstIdOfASeedOfZeroIsOne() {
        assertThat(new RequestIds(seeds(0)).next(id -> false)).isEqualTo(1);
    }

    @Test
    void anIdInFlightIsSkipped() {
        var ids = new RequestIds(seeds(9));
        Set<Long> inFlight = Set.of(10L, 11L);
        assertThat(ids.next(inFlight::contains)).isEqualTo(12);
    }

    @Test
    void theSeedIsDrawnFromThePositive31BitRange() {
        var bounds = new ArrayDeque<Long>();
        var random = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new AssertionError("an unbounded draw");
            }

            @Override
            public long nextLong(long bound) {
                bounds.add(bound);
                return bound - 1;
            }
        };
        assertThat(new RequestIds(random).next(id -> false)).isEqualTo(Integer.MAX_VALUE);
        assertThat(bounds).containsExactly((long) Integer.MAX_VALUE);
    }

    /** A random generator that draws these seeds in turn. */
    private static RandomGenerator seeds(long... seeds) {
        var queue = new ArrayDeque<Long>();
        for (long seed : seeds) {
            queue.add(seed);
        }
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new AssertionError("an unbounded draw");
            }

            @Override
            public long nextLong(long bound) {
                long seed = queue.removeFirst();
                assertThat(seed).isBetween(0L, bound - 1);
                return seed;
            }
        };
    }
}
