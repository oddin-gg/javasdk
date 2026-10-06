package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** What the feed does before {@code open()}: the managers work, the feed's queues do not exist yet. */
class BeforeOpenScenarioIT {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String EVENT_RECOVERY = "/v1/live/odds/events/" + MATCH + "/initiate_request";

    /**
     * An event recovery asked for before the feed opens: 0.0.x sends it and returns its request id,
     * but no queue exists yet for what the recovery sends, so it is lost unseen; 1.0 does not send it
     * and returns null.
     */
    @Test
    void anEventRecoveryAskedForBeforeOpenIsNotSent() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Long requestId = sdk.oddsFeed().getRecoveryManager().initiateEventOddsMessagesRecovery(2, MATCH);

            KnownDifference.EVENT_RECOVERY_BEFORE_OPEN_IS_SENT.expect(
                    () -> {
                        assertThat(requestId).as("the request id 0.0.x returns").isNotNull();
                        assertThat(rest.awaitRequest("POST", EVENT_RECOVERY).parameter("request_id"))
                                .as("the request id sent")
                                .isEqualTo(String.valueOf(requestId));
                        // what the recovery would send now reaches no queue
                        var recovered = Fixtures.read(ODDS_CHANGE)
                                .replace("request_id=\"2049987833\"", "request_id=\"" + requestId + "\"");
                        assertThat(feed.publish(recovered))
                                .as("the recovered message routed to a queue")
                                .isFalse();
                        Received received = sdk.open(MessageInterest.ALL);
                        assertThat(received.poll(Message.class, Duration.ofSeconds(2)))
                                .as("what the session gets of the recovery once open")
                                .isEmpty();
                    },
                    () -> {
                        assertThat(requestId).as("the request id 1.0 returns").isNull();
                        assertThat(rest.requests("POST", EVENT_RECOVERY))
                                .as("event recovery requests")
                                .isEmpty();
                    });
        }
    }

    /**
     * The raw API data of a call made before the feed opens: 0.0.x hands the extended listener only
     * what comes once the feed is open, 1.0 every response from the start on.
     */
    @Test
    void rawApiDataBeforeOpenReachesTheExtendedListener() throws InterruptedException {
        var raw = new RawApiData();
        try (FakeRestServer rest = FakeRestServer.start();
                Sdk sdk = Sdk.withoutFeed(rest, raw)) {
            assertThat(sdk.oddsFeed().getSportsInfoManager().getMatch(MATCH).getName(Locale.ENGLISH))
                    .isEqualTo("Team Alpha vs Team Beta");

            KnownDifference.RAW_API_DATA_ONLY_ONCE_OPEN.expect(
                    () -> assertThat(raw.next(Duration.ofSeconds(2)))
                            .as("raw API data before open()")
                            .isNull(),
                    () -> {
                        URI first = raw.next(Duration.ofSeconds(10));
                        assertThat(first).as("raw API data before open()").isNotNull();
                    });
        }
    }

    /** The extended listener of a feed that is never opened: it keeps the URIs of the raw API data. */
    private static final class RawApiData implements OddsFeedExtListener {
        private final BlockingQueue<URI> uris = new LinkedBlockingQueue<>();

        URI next(Duration wait) throws InterruptedException {
            return uris.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void onRawFeedMessageReceived(
                UnparsedMessage message,
                MessageInterest messageInterest,
                RoutingKeyInfo routingKey,
                MessageTimestamp timestamp) {}

        @Override
        public void onRawApiDataReceived(URI uri, Object data) {
            uris.add(uri);
        }
    }
}
