package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * An SDK node: a client running several SDKs against one bookmaker gives each a node id, and the
 * feed addresses a message to one node or to none, which every node takes. The id is in the last
 * part of the routing key; the fake feed addresses a message to a node with {@code publishToNode}.
 * Both lines bind the same keys, and send the node's id with each recovery request.
 */
class NodeRoutingScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String LIVE_RECOVERY = "/v1/live/recovery/initiate_request";
    private static final int NODE = 7;
    private static final int OTHER_NODE = 8;

    /** The odds change of a match, live rather than a recovery's: it carries no request id. */
    private static String oddsChange(String match) {
        return Fixtures.replace(
                Fixtures.replace(Fixtures.read(ODDS_CHANGE), " request_id=\"2049987833\"", ""),
                "od:match:198314",
                "od:match:" + match);
    }

    /**
     * A session of node 7 is sent a message for node 7, one for no node and one for node 8. It gets
     * the first two, in that order, and the broker routes the third to no queue at all.
     */
    @Test
    void aSessionTakesItsNodesMessagesAndThoseForNoNodeAndNotAnotherNodes() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed, builder -> builder.setSDKNodeId(NODE))) {
            Received received = sdk.open(MessageInterest.ALL);

            assertThat(feed.publishToNode(NODE, oddsChange("1")))
                    .as("the node's own message routed")
                    .isTrue();
            assertThat(feed.publish(oddsChange("2")))
                    .as("the message for no node routed")
                    .isTrue();
            assertThat(feed.publishToNode(OTHER_NODE, oddsChange("3")))
                    .as("another node's message routed")
                    .isFalse();
            // another of the node's, after the other node's: it follows the second in the listener
            assertThat(feed.publishToNode(NODE, oddsChange("4")))
                    .as("the node's own second message routed")
                    .isTrue();

            assertThat(received.next(OddsChange.class).getEvent().getId())
                    .as("first odds change")
                    .isEqualTo(URN.parse("od:match:1"));
            assertThat(received.next(OddsChange.class).getEvent().getId())
                    .as("second odds change")
                    .isEqualTo(URN.parse("od:match:2"));
            assertThat(received.next(OddsChange.class).getEvent().getId())
                    .as("third odds change: the other node's did not come between")
                    .isEqualTo(URN.parse("od:match:4"));
            assertThat(received.poll(OddsChange.class, Duration.ofSeconds(1)))
                    .as("a fourth odds change")
                    .isEmpty();
        }
    }

    /** An SDK with no node id binds every node's messages: it is one node among none. */
    @Test
    void aSessionWithoutANodeIdTakesTheMessagesOfEveryNode() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);

            assertThat(feed.publishToNode(NODE, oddsChange("1")))
                    .as("a node's message routed")
                    .isTrue();
            assertThat(feed.publishToNode(OTHER_NODE, oddsChange("2")))
                    .as("another node's message routed")
                    .isTrue();
            assertThat(feed.publish(oddsChange("3")))
                    .as("the message for no node routed")
                    .isTrue();

            for (var match : new String[] {"1", "2", "3"}) {
                assertThat(received.next(OddsChange.class).getEvent().getId())
                        .as("odds change of match " + match)
                        .isEqualTo(URN.parse("od:match:" + match));
            }
        }
    }

    /**
     * The SDK of node 7 asks for its recovery with its node id; the snapshot complete the API sends
     * to node 8 does not reach it and the producer stays down, the one for node 7 completes it.
     */
    @Test
    void aRecoveryIsAskedForWithTheNodeAndCompletedByTheNodesSnapshotComplete() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed, builder -> builder.setSDKNodeId(NODE))) {
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(2, true));
            RecordedRequest recovery =
                    rest.awaitRequests("POST", LIVE_RECOVERY, 1).getFirst();
            assertThat(recovery.parameter("node_id"))
                    .as("node id of the recovery")
                    .isEqualTo("7");
            long requestId = Long.parseLong(recovery.parameter("request_id"));

            assertThat(feed.publishToNode(OTHER_NODE, snapshotComplete(2, requestId)))
                    .as("the other node's snapshot complete routed")
                    .isFalse();
            assertThat(sdk.events().pollProducerStatus(2, Duration.ofSeconds(1)))
                    .as("a status change of producer 2 before its snapshot complete")
                    .isEmpty();

            assertThat(feed.publishToNode(NODE, snapshotComplete(2, requestId)))
                    .as("the node's snapshot complete routed")
                    .isTrue();
            assertThat(sdk.events().nextProducerStatus(2).isDown())
                    .as("down after the node's snapshot complete")
                    .isFalse();
        }
    }
}
