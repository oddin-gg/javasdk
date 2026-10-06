package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The sessions of a feed: they are built before {@code open()}, which binds their queues once. */
class SessionScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";

    /**
     * A session built once the feed is open: 0.0.x hands one out and never delivers to it; 1.0
     * refuses to build it, so the mistake shows where it is made.
     */
    @Test
    void aSessionBuiltAfterOpenIsRefused() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received opened = sdk.open(MessageInterest.ALL);
            var late = new Received();
            var builder = sdk.oddsFeed().getSessionBuilder().setListener(late).setMessageInterest(MessageInterest.ALL);
            KnownDifference.Check delivered = () -> {
                feed.publishFixture(ODDS_CHANGE);
                assertThat(opened.next(OddsChange.class))
                        .as("what the session built before open() gets")
                        .isNotNull();
            };

            KnownDifference.SESSION_BUILT_AFTER_OPEN_RECEIVES_NOTHING.expect(
                    () -> {
                        assertThat(builder.build())
                                .as("the session built after open()")
                                .isNotNull();
                        delivered.run();
                        assertThat(late.poll(Message.class, Duration.ofSeconds(2)))
                                .as("what the session built after open() gets")
                                .isEmpty();
                    },
                    () -> {
                        assertThat(catchThrowable(builder::build))
                                .as("building a session after open()")
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("the feed is already open; build sessions before open()");
                        delivered.run();
                    });
        }
    }
}
