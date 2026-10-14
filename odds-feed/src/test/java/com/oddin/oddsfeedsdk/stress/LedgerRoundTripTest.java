package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The ledger's whole path with a real feed: its messages are published to the broker, decoded by the
 * SDK, told to the listener and recorded - and the checks then say what the stress scenarios rely on.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LedgerRoundTripTest {

    private static final String ONE = "od:match:9201";
    private static final String TWO = "od:match:9202";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void whatIsPublishedForTwoMatchesIsDeliveredExactlyAndCounted() throws Exception {
        try (var broker = FakeFeed.start();
                var api = FakeRestServer.start();
                var feed = openFeed(broker, api, new Ledger(System.currentTimeMillis()))) {
            var ledger = feed.ledger;
            for (int i = 0; i < 4; i++) {
                assertThat(ledger.publish(broker, ONE)).isTrue();
                assertThat(ledger.publish(broker, TWO)).isTrue();
            }
            var mark = ledger.mark();
            assertThat(ledger.publish(broker, ONE)).isTrue();

            assertThat(ledger.awaitCaughtUp(WAIT)).isTrue();

            ledger.assertExact();
            ledger.assertPrefix();
            ledger.assertGapsOnlyAfterReconnect(mark);
            assertThat(ledger.publishedCount()).isEqualTo(9);
            assertThat(ledger.deliveredCount()).isEqualTo(9);
            assertThat(mark).containsEntry(ONE, 4L).containsEntry(TWO, 4L);
            assertThat(ledger.publishedSince(mark)).isEqualTo(1);
            assertThat(ledger.deliveredSince(mark)).isEqualTo(1);
        }
    }

    private static Run openFeed(FakeFeed broker, FakeRestServer api, Ledger ledger) throws Exception {
        var feed = StressFeed.feed(new StressFeed.QuietEvents() {}, broker, api, builder -> builder);
        feed.getSessionBuilder()
                .setListener(ledger.listener())
                .setMessageInterest(MessageInterest.ALL)
                .build();
        feed.open();
        StressFeed.awaitRouted(() -> broker.publish(StressFeed.fixtureChange("od:match:9200", 1)), "the probe", WAIT);
        return new Run(feed, ledger);
    }

    private record Run(OddsFeed feed, Ledger ledger) implements AutoCloseable {
        @Override
        public void close() {
            feed.close();
        }
    }
}
