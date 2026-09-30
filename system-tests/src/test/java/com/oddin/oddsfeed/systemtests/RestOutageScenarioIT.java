package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The REST API going down while the feed is open. Feed messages carry ids, not names, so they keep
 * arriving; what needs REST fails for as long as it is down and works again once it is back.
 */
class RestOutageScenarioIT {

    private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
    private static final String PREMATCH_RECOVERY = "/v1/pre/recovery/initiate_request";

    /**
     * Messages keep arriving through an outage, and a getter that needs REST fails - under the
     * default exception strategy, with an SDK exception. The failure is not remembered: once the API
     * is back, the same getter on the same match loads it.
     */
    @Test
    void messagesKeepArrivingThroughAnOutageAndEntitiesLoadOnceItEnds() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            Received received = sdk.open(MessageInterest.ALL);
            rest.startOutage(503);
            feed.publishFixture(ODDS_CHANGE);

            OddsChange<?> oddsChange = received.next(OddsChange.class);
            var match = (Match) oddsChange.getEvent();
            assertThatThrownBy(() -> match.getName(Locale.ENGLISH))
                    .as("the match name while the API is down")
                    .isInstanceOf(OddsFeedSdkException.class);

            rest.endOutage();
            assertThat(match.getName(Locale.ENGLISH))
                    .as("the match name once the API is back")
                    .isEqualTo("Team Alpha vs Team Beta");
        }
    }

    /**
     * A recovery request the API refuses leaves the producer down. 0.0.x marks the recovery as
     * started anyway and asks again only once the maximum recovery time has passed - six hours by
     * default - so the alives that arrive after the API is back change nothing. 1.0 re-issues it
     * with backoff.
     */
    @Test
    void aRecoveryRequestTheApiRefusedIsAskedForAgain() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            rest.startOutage(503);
            feed.publish(alive(1, true));
            rest.awaitRequest("POST", PREMATCH_RECOVERY);
            rest.endOutage();

            KnownDifference.FAILED_RECOVERY_IS_NOT_RETRIED.expect(
                    () -> assertThat(recoveriesWhileAlive(rest, feed, Duration.ofSeconds(3)))
                            .as("recovery requests of producer 1, with an alive every second after the outage")
                            .isEqualTo(1),
                    // the backoff is the recovery actor's to choose; it has to fit in this wait
                    () -> assertThat(recoveriesWhileAlive(rest, feed, Duration.ofSeconds(30)))
                            .as("recovery requests of producer 1, with an alive every second after the outage")
                            .isGreaterThanOrEqualTo(2));
            assertThat(sdk.oddsFeed().getProducerManager().isProducerDown(1))
                    .as("producer 1 down, without a completed recovery")
                    .isTrue();
        }
    }

    /**
     * How many recovery requests of producer 1 there are after sending it an alive every second
     * for {@code wait}, or until a second request arrives.
     */
    private static int recoveriesWhileAlive(FakeRestServer rest, FakeFeed feed, Duration wait)
            throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (rest.requests("POST", PREMATCH_RECOVERY).size() < 2 && System.nanoTime() < deadline) {
            feed.publish(alive(1, true));
            Thread.sleep(Duration.ofSeconds(1));
        }
        return rest.requests("POST", PREMATCH_RECOVERY).size();
    }
}
