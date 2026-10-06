package com.oddin.oddsfeedsdk.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The fixture changes delivered: the key 0.0.x kept, for an hour, bounded. */
class FixtureChangesTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @Test
    void aChangeIsNewOncePerProducerEventAndTimestamp() {
        var changes = new FixtureChanges(100, ticker);
        assertThat(changes.first(2, URN.parse("od:match:1"), 10)).isTrue();
        assertThat(changes.first(2, URN.parse("od:match:1"), 10)).isFalse();
        assertThat(changes.first(1, URN.parse("od:match:1"), 10))
                .as("another producer")
                .isTrue();
        assertThat(changes.first(2, URN.parse("od:match:2"), 10))
                .as("another event")
                .isTrue();
        assertThat(changes.first(2, URN.parse("od:match:1"), 11))
                .as("another timestamp")
                .isTrue();
    }

    @Test
    void anEventIdLongerThanAnyTheFeedHasIsNotRemembered() {
        var changes = new FixtureChanges(100, ticker);
        URN tournament = URN.parse("od:tournament:" + Long.MAX_VALUE);
        assertThat(changes.first(2, tournament, 10)).isTrue();
        assertThat(changes.first(2, tournament, 10))
                .as("the longest the feed has")
                .isFalse();
        URN tooLong = URN.parse("x".repeat(16_384) + ":match:1");
        assertThat(changes.first(2, tooLong, 10)).isTrue();
        assertThat(changes.first(2, tooLong, 10)).as("new every time").isTrue();
        assertThat(changes.size()).isEqualTo(1);
    }

    @Test
    void aChangeIsRememberedForAnHour() {
        var changes = new FixtureChanges(100, ticker);
        changes.first(2, URN.parse("od:match:1"), 10);
        nanos.addAndGet(Duration.ofMinutes(59).toNanos());
        assertThat(changes.first(2, URN.parse("od:match:1"), 10)).isFalse();
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(changes.first(2, URN.parse("od:match:1"), 10))
                .as("an hour later")
                .isTrue();
        assertThat(changes.evicted()).as("expired, not evicted").isZero();
    }

    @Test
    void aChangeDroppedForRoomIsCounted() {
        var changes = new FixtureChanges(10, ticker);
        for (int i = 0; i < 50; i++) {
            changes.first(2, URN.parse("od:match:" + i), 10);
        }
        changes.cleanUp();
        assertThat(changes.evicted()).isEqualTo(40);
    }
}
