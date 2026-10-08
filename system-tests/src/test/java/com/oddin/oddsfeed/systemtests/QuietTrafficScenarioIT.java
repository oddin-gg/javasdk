package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A stand-in for an hour of normal traffic on the test environment: a few hundred odds changes,
 * bet stops and fixture changes over three matches, with the alives of both producers between
 * them, sent with no pause beyond the delivery of each message. What the SDK logs at INFO or above
 * after the open is a state change from a short list, and nothing is logged per message.
 */
class QuietTrafficScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String BET_STOP = "feed/bet_stop/bet_stop_minimal.xml";
    private static final String FIXTURE_CHANGE = "feed/fixture_change/fixture_change.xml";
    private static final List<String> MATCHES = List.of("od:match:198314", "od:match:198315", "od:match:198316");

    /** Of these, every fifth is a bet stop and every twentieth a fixture change; the rest are odds changes. */
    private static final int MESSAGES = 400;

    /** One alive of each producer for this many messages, so the feed never reads as silent. */
    private static final int ALIVE_EVERY = 20;

    /**
     * The state changes a client reads at INFO or above: the producers up and down, the recoveries
     * start and end, a session catches up, a part of the feed changes health. The messages, as the
     * SDK words them, start with these; the logger is named in front of each. Both lines pass the same
     * test: 0.0.x logs the recoveries only, and nothing per message.
     */
    private static final List<Pattern> STATE_CHANGES = List.of(
            Pattern.compile("INFO \\S+: Odds feed opened with \\d+ session\\(s\\)"),
            Pattern.compile("INFO \\S+: Producer \\d+ (up|down): .*"),
            Pattern.compile("INFO \\S+: Recovery \\d+ of producer \\d+ .*"),
            // 0.0.x words a recovery's start and end alike, and logs no producer or health change at INFO
            Pattern.compile("INFO \\S+: Recovery (started|finished) for request \\d+.*"),
            Pattern.compile("INFO \\S+: Recovery of producer \\d+ re-armed after the cool-down"),
            Pattern.compile("INFO \\S+: Session \\d+ caught up"),
            Pattern.compile("INFO \\S+: The feed's \\S+ is \\S+ now: .*"));

    @Test
    void normalTrafficLogsOnlyStateChanges() throws InterruptedException {
        try (LogCapture logs = LogCapture.start();
                FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            logs.clear();

            bringUp(sdk, rest, feed, 1, "pre");
            bringUp(sdk, rest, feed, 2, "live");
            var beforeTraffic = logs.atLeastInfoFrom("com.oddin");

            var delivered = publishTraffic(feed, received);

            assertThat(delivered).as("messages the listener got").isEqualTo(MESSAGES);
            var afterTraffic = logs.atLeastInfoFrom("com.oddin");
            assertThat(afterTraffic)
                    .as("what is logged at INFO or above while the traffic flows: nothing, per message or at all")
                    .isEqualTo(beforeTraffic);
            assertThat(afterTraffic)
                    .as("what is logged at INFO or above after the open")
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(STATE_CHANGES)
                            .as("an allowed state change: " + line)
                            .anyMatch(allowed -> allowed.matcher(line).matches()));
        }
    }

    /** An alive, the recovery it asks for and its snapshot complete: the producer is up. */
    private static void bringUp(Sdk sdk, FakeRestServer rest, FakeFeed feed, int producer, String name)
            throws InterruptedException {
        feed.publish(FeedMessages.alive(producer, true));
        var requestId = Long.parseLong(rest.awaitRequest("POST", "/v1/" + name + "/recovery/initiate_request")
                .parameter("request_id"));
        feed.publish(FeedMessages.snapshotComplete(producer, requestId));
        assertThat(sdk.events().nextProducerStatus(producer).isDown())
                .as("producer " + producer + " down after its recovery")
                .isFalse();
    }

    /** The messages in turn, each waited for; an alive of both producers after every few. Returns those delivered. */
    private static int publishTraffic(FakeFeed feed, Received received) throws InterruptedException {
        var odds = Fixtures.read(ODDS_CHANGE);
        var betStop = FeedMessages.fromProducer(Fixtures.read(BET_STOP), 3, 2);
        var fixtureChange = Fixtures.read(FIXTURE_CHANGE);
        var delivered = 0;
        for (var i = 0; i < MESSAGES; i++) {
            var match = MATCHES.get(i % MATCHES.size());
            var message = i % 20 == 9
                    ? fixtureChange
                    : i % 5 == 4 ? betStop : odds.replace("odds=\"1.5\"", "odds=\"1." + (i % 90 + 10) + "\"");
            assertThat(feed.publish(message.replace(MATCHES.getFirst(), match)))
                    .as("routed to the SDK's queue")
                    .isTrue();
            received.next(Message.class);
            delivered++;
            if (i % ALIVE_EVERY == 0) {
                feed.publish(FeedMessages.alive(1, true));
                feed.publish(FeedMessages.alive(2, true));
            }
        }
        return delivered;
    }
}
