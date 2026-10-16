package com.oddin.oddsfeedsdk.internal.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class DeadlineTest {

    private static final long HOURS_5 = Duration.ofHours(5).toNanos();

    /** A clock that reads {@code nanos} and stays there. */
    private static LongSupplier clockAt(long nanos) {
        return () -> nanos;
    }

    @Test
    void theOneWithLessLeftWinsWhenTheClocksReadHoursApart() {
        // 10 s left on a clock at 5 h: its end reading is far above the 30 s left on a clock at 0
        var soon = Deadline.in(Duration.ofSeconds(10), clockAt(HOURS_5));
        var later = Deadline.in(Duration.ofSeconds(30), clockAt(0));

        assertThat(soon.endNanos()).isGreaterThan(later.endNanos());
        assertThat(soon.earlier(later)).isSameAs(soon);
        assertThat(later.earlier(soon)).isSameAs(soon);
    }

    @Test
    void theOneWithLessLeftWinsWhenItIsOnTheLowerClockToo() {
        // the order the end readings give is the order of time left: the other way round from above
        var soon = Deadline.in(Duration.ofSeconds(10), clockAt(0));
        var later = Deadline.in(Duration.ofSeconds(30), clockAt(HOURS_5));

        assertThat(soon.endNanos()).isLessThan(later.endNanos());
        assertThat(soon.earlier(later)).isSameAs(soon);
        assertThat(later.earlier(soon)).isSameAs(soon);
    }

    @Test
    void whatIsLeftNowCountsNotTheBudgetItStartedWith() {
        var ticking = new AtomicLong();
        var longBudget = Deadline.in(Duration.ofSeconds(60), ticking::get);
        var fresh = Deadline.in(Duration.ofSeconds(10), clockAt(HOURS_5));

        assertThat(longBudget.earlier(fresh)).as("60 s left against 10 s").isSameAs(fresh);

        ticking.set(Duration.ofSeconds(55).toNanos());

        assertThat(longBudget.remaining()).isEqualTo(Duration.ofSeconds(5));
        assertThat(longBudget.budget()).isGreaterThan(fresh.budget());
        assertThat(longBudget.earlier(fresh))
                .as("5 s left against 10 s, from a budget of 60 s")
                .isSameAs(longBudget);
        assertThat(fresh.earlier(longBudget)).isSameAs(longBudget);
    }

    @Test
    void aPassedDeadlineWinsOverALiveOneOnAnotherClock() {
        var passed = new Deadline(5, Duration.ofSeconds(1), clockAt(HOURS_5));
        var live = Deadline.in(Duration.ofSeconds(30), clockAt(0));

        assertThat(passed.passed()).isTrue();
        assertThat(passed.earlier(live)).isSameAs(passed);
        assertThat(live.earlier(passed)).isSameAs(passed);
    }

    @Test
    void twoPassedDeadlinesReturnTheReceiver() {
        var first = new Deadline(1, Duration.ofSeconds(1), clockAt(HOURS_5));
        var second = new Deadline(HOURS_5 + 1, Duration.ofSeconds(1), clockAt(HOURS_5 + 9));

        assertThat(first.passed()).isTrue();
        assertThat(second.passed()).isTrue();
        assertThat(first.earlier(second)).isSameAs(first);
        assertThat(second.earlier(first)).isSameAs(second);
    }

    @Test
    void equalTimeLeftReturnsTheReceiver() {
        var first = Deadline.in(Duration.ofSeconds(10), clockAt(HOURS_5));
        var second = Deadline.in(Duration.ofSeconds(10), clockAt(0));

        assertThat(first.earlier(second)).isSameAs(first);
        assertThat(second.earlier(first)).isSameAs(second);
    }
}
