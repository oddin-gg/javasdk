package com.oddin.oddsfeed.systemtests.next;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.NextOnly;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import org.junit.jupiter.api.Test;

/**
 * The snapshot completes of recoveries another instance asked for, which the broker routes to this
 * one's queue too, as it binds them as 0.0.x does: a session of interest ALL with no node id takes
 * every instance's, whatever its node id; with a node id, those of its node and of the instances
 * without one; any other interest takes those of its own node, or, with no node id, of the instances
 * without one. Such a completion completes nothing here: it is counted, logged at DEBUG, and told
 * once at INFO with the advice to set a node id, and never at WARN, as there can be thousands.
 */
@NextOnly
class StraySnapshotCompleteScenarioIT {

    private static final String LIVE_RECOVERY = "/v1/live/recovery/initiate_request";
    private static final String ACTOR = "com.oddin.oddsfeedsdk.internal.recovery.RecoveryActor";
    private static final int NODE = 7;
    private static final int OTHER_NODE = 8;

    /**
     * Another instance without a node id, and one with a node id, both recover: this feed, which has
     * none and takes every message, hears both. The producer comes up by this feed's own completion,
     * sent after the strays, which the session takes in order, so they are handled by then.
     */
    @Test
    void aFeedWithoutANodeIdOfInterestAllTakesTheCompletionsOfEveryInstanceAndLogsThemQuietly()
            throws InterruptedException {
        try (LogCapture logs = LogCapture.start();
                FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(2, true));
            long requestId =
                    Long.parseLong(rest.awaitRequest("POST", LIVE_RECOVERY).parameter("request_id"));

            assertThat(feed.publish(snapshotComplete(2, 53_059_002L)))
                    .as("the completion of another instance without a node id routed")
                    .isTrue();
            assertThat(feed.publishToNode(OTHER_NODE, snapshotComplete(2, 53_059_003L)))
                    .as("the completion of an instance with a node id routed")
                    .isTrue();
            assertThat(feed.publish(snapshotComplete(2, 53_059_004L)))
                    .as("a second completion of an instance without a node id routed")
                    .isTrue();
            assertThat(feed.publish(snapshotComplete(2, requestId)))
                    .as("this feed's own completion routed")
                    .isTrue();
            assertThat(sdk.events().nextProducerStatus(2).isDown())
                    .as("down after this feed's own completion")
                    .isFalse();

            assertThat(sdk.oddsFeed().getHealth().counters())
                    .as("the completions dropped as strays")
                    .containsEntry("recovery.unknown_completions", 3L);
            assertThat(logs.everythingFrom(ACTOR))
                    .as("what the recovery actor logged: the first stray told once at INFO")
                    .filteredOn(line -> line.startsWith("INFO "))
                    .singleElement()
                    .asString()
                    .startsWith("INFO " + ACTOR + ": A snapshot complete of request 53059002 arrived which this feed"
                            + " did not ask for: another instance of this bookmaker asked for it. A feed without a"
                            + " node id takes")
                    .contains("(setSDKNodeId)");
            assertThat(logs.everythingFrom(ACTOR))
                    .as("what the recovery actor logged: each of them at DEBUG")
                    .filteredOn(line -> line.startsWith("DEBUG ") && line.contains("which this feed did not ask for"))
                    .containsExactly(
                            "DEBUG " + ACTOR + ": A snapshot complete of request 53059002, which this feed did not"
                                    + " ask for, is dropped, 1 so far",
                            "DEBUG " + ACTOR + ": A snapshot complete of request 53059003, which this feed did not"
                                    + " ask for, is dropped, 2 so far",
                            "DEBUG " + ACTOR + ": A snapshot complete of request 53059004, which this feed did not"
                                    + " ask for, is dropped, 3 so far");
            assertThat(logs.warningsFrom("com.oddin"))
                    .as("what the SDK logged at WARN or above")
                    .isEmpty();
        }
    }

    /**
     * With a node id and interest ALL, a feed takes the completions of its node and of the instances
     * without one, not those of another node: it still hears the instances that have no node id.
     */
    @Test
    void aFeedWithANodeIdOfInterestAllTakesItsNodesAndThoseWithoutOneAndTellsAStrayOnceWithTheNode()
            throws InterruptedException {
        try (LogCapture logs = LogCapture.start();
                FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed, builder -> builder.setSDKNodeId(NODE))) {
            sdk.open(MessageInterest.ALL);
            feed.publish(alive(2, true));
            long requestId =
                    Long.parseLong(rest.awaitRequest("POST", LIVE_RECOVERY).parameter("request_id"));

            assertThat(feed.publish(snapshotComplete(2, 53_059_002L)))
                    .as("the completion of an instance without a node id routed")
                    .isTrue();
            assertThat(feed.publishToNode(OTHER_NODE, snapshotComplete(2, 53_059_003L)))
                    .as("the completion of another node routed")
                    .isFalse();
            assertThat(feed.publishToNode(NODE, snapshotComplete(2, 53_059_004L)))
                    .as("the completion of another instance with the same node id routed")
                    .isTrue();
            assertThat(feed.publishToNode(NODE, snapshotComplete(2, requestId)))
                    .as("this feed's own completion routed")
                    .isTrue();
            assertThat(sdk.events().nextProducerStatus(2).isDown())
                    .as("down after this feed's own completion")
                    .isFalse();

            assertThat(sdk.oddsFeed().getHealth().counters())
                    .as("the completions dropped as strays")
                    .containsEntry("recovery.unknown_completions", 2L);
            assertThat(logs.everythingFrom(ACTOR))
                    .as("what the recovery actor logged of it: one INFO naming the node id")
                    .filteredOn(line -> line.startsWith("INFO "))
                    .singleElement()
                    .asString()
                    .contains("request 53059002 arrived which this feed did not ask for")
                    .contains("this feed's node id 7")
                    .contains("(setSDKNodeId)");
            assertThat(logs.warningsFrom("com.oddin"))
                    .as("what the SDK logged at WARN or above")
                    .isEmpty();
        }
    }

    /**
     * Any other interest binds the completions of one node key only: without a node id, those of
     * the instances without one, and not those of a node.
     */
    @Test
    void aFeedWithoutANodeIdOfAnotherInterestTakesOnlyTheCompletionsOfInstancesWithoutOne()
            throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.LIVE_ONLY);

            assertThat(feed.publish(snapshotComplete(2, 53_059_002L)))
                    .as("the completion of an instance without a node id routed")
                    .isTrue();
            assertThat(feed.publishToNode(OTHER_NODE, snapshotComplete(2, 53_059_003L)))
                    .as("the completion of an instance with a node id routed")
                    .isFalse();
        }
    }

    /** With a node id, the completions of that node only: not those of the instances without one. */
    @Test
    void aFeedWithANodeIdOfAnotherInterestTakesOnlyItsNodesCompletions() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed, builder -> builder.setSDKNodeId(NODE))) {
            sdk.open(MessageInterest.LIVE_ONLY);

            assertThat(feed.publish(snapshotComplete(2, 53_059_002L)))
                    .as("the completion of an instance without a node id routed")
                    .isFalse();
            assertThat(feed.publishToNode(OTHER_NODE, snapshotComplete(2, 53_059_003L)))
                    .as("the completion of another node routed")
                    .isFalse();
            assertThat(feed.publishToNode(NODE, snapshotComplete(2, 53_059_004L)))
                    .as("the completion of this feed's node routed")
                    .isTrue();
        }
    }
}
