package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static com.oddin.oddsfeed.fakes.FeedMessages.snapshotComplete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FeedMessages;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A producer whose clock is not the SDK's: its messages are stamped ahead of or behind the time
 * they arrive, by more than any delivery takes. The fake feed skews the producers' clock as a
 * whole, so every message and alive it publishes carries the producers' now.
 *
 * <p>Both lines share what a client sees: the producer comes up and stays up, its messages are
 * delivered with the stamp the producer gave them, and a recovery after a gap starts from the
 * producer's time, not the SDK's. What 1.0 does with the offset beyond that - the safety net
 * reads ages by it - is in {@code ClockSkewSafetyNetScenarioIT}.
 */
class ClockSkewScenarioIT {

    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";
    private static final String LIVE_RECOVERY = "/v1/live/recovery/initiate_request";
    private static final String WITH_STATUS = "feed/odds_change/odds_change_closed_with_winner.xml";

    /** A live odds change from producer 1, carrying no request id, so not a recovery's. */
    private static final String LIVE_ODDS_CHANGE = Fixtures.replace(
            FeedMessages.fromProducer(Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), 2, 1),
            " request_id=\"2049987833\"",
            "");

    /** How far a stamp may be from the producers' clock for the time it takes to get here. */
    private static final long CLOSE = 15_000;

    /**
     * Producer 1 sends an alive and its recovery completes: it comes up and no status change
     * follows while it sends more alives and a live message. Then it says it is no longer
     * subscribed: it goes down, and the recovery that follows starts from the producer's time, not
     * the SDK's, and brings it up again.
     */
    @ParameterizedTest(name = "a producer {0} s from the SDK's clock")
    @ValueSource(longs = {-30, 30})
    void aProducerWhoseClockIsOffComesUpStaysUpAndRecoversFromItsOwnTime(long seconds) throws InterruptedException {
        var skew = Duration.ofSeconds(seconds);
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            feed.skewClock(skew);
            Received received = sdk.open(MessageInterest.ALL);
            feed.publish(alive(1, true));
            feed.publish(snapshotComplete(
                    1,
                    requestId(rest.awaitRequests("POST", PREMATCH_RECOVERY, 1).getFirst())));
            var up = sdk.events().nextProducerStatus(1);
            assertThat(up.isDown()).as("down after the first recovery").isFalse();

            feed.publish(LIVE_ODDS_CHANGE);
            OddsChange<?> oddsChange = received.next(OddsChange.class);
            assertThat(oddsChange.getTimestamp().getCreated())
                    .as("created: the producer's now, as it stamped it")
                    .isCloseTo(System.currentTimeMillis() + skew.toMillis(), within(CLOSE));
            feed.publish(alive(1, true));
            feed.publish(LIVE_ODDS_CHANGE);
            assertThat(received.next(OddsChange.class))
                    .as("a second odds change")
                    .isNotNull();
            assertThat(sdk.events().pollProducerStatus(1, Duration.ofSeconds(3)))
                    .as("a status change of producer 1 while it sends alives and messages")
                    .isEmpty();
            assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                    .as("producer 1 down")
                    .isFalse();

            feed.publish(alive(1, false));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the unsubscribed alive")
                    .isTrue();
            RecordedRequest recovery =
                    rest.awaitRequests("POST", PREMATCH_RECOVERY, 2).get(1);
            assertThat(recovery.parameter("after"))
                    .as("where the recovery after the down starts; none would mean a full snapshot")
                    .isNotNull();
            assertThat(Long.parseLong(recovery.parameter("after")))
                    .as("where the recovery after the down starts: at the producer's time, not the SDK's")
                    .isCloseTo(System.currentTimeMillis() + skew.toMillis(), within(CLOSE));

            feed.publish(snapshotComplete(1, requestId(recovery)));
            assertThat(sdk.events().nextProducerStatus(1).isDown())
                    .as("down after the second recovery")
                    .isFalse();
        }
    }

    /**
     * A producer whose clock is further off than the 20 minutes after which a message is old: what
     * it sends is current by its own clock, so its match status still reaches the match. The
     * message says 7 for the home score, the summary 3; 0.0.x writes the message's, and 1.0 does
     * since it reads the age with the producer's offset, measured on the producer's alive (KD-14).
     */
    @ParameterizedTest(name = "a producer {0} s from the SDK's clock")
    @ValueSource(longs = {-1_500, 1_500})
    void aProducerWhoseClockIsFarOffStillWritesTheStatusOfItsMessages(long seconds) throws InterruptedException {
        var skew = Duration.ofSeconds(seconds);
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            feed.skewClock(skew);
            Received received = sdk.open(MessageInterest.ALL);
            feed.publish(alive(2, true));
            // the request for the alive's producer: the alive has been seen, and its clock measured
            rest.awaitRequests("POST", LIVE_RECOVERY, 1);
            feed.publish(Fixtures.replace(Fixtures.read(WITH_STATUS), "home_score=\"3\"", "home_score=\"7\""));

            OddsChange<?> oddsChange = received.next(OddsChange.class);
            assertThat(oddsChange.getTimestamp().getCreated())
                    .as("created: the producer's now, as it stamped it")
                    .isCloseTo(System.currentTimeMillis() + skew.toMillis(), within(CLOSE));
            assertThat(((Match) oddsChange.getEvent()).getStatus().getHomeScore())
                    .as("home score, from the message")
                    .isEqualTo(7.0);
        }
    }

    private static long requestId(RecordedRequest recovery) {
        var requestId = recovery.parameter("request_id");
        assertThat(requestId).as("request id of " + recovery.path()).isNotBlank();
        return Long.parseLong(requestId);
    }
}
