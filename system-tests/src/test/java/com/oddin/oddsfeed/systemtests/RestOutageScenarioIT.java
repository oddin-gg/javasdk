package com.oddin.oddsfeed.systemtests;

import static com.oddin.oddsfeed.fakes.FeedMessages.alive;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeFeed;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.api.entities.sportevent.Match;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
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
     * default exception strategy, with the {@link ItemNotFoundException} 0.0.x threw. The failure is
     * not remembered: once the API is back, the same getter on the same match loads it.
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
                    .isExactlyInstanceOf(ItemNotFoundException.class);

            rest.endOutage();
            assertThat(match.getName(Locale.ENGLISH))
                    .as("the match name once the API is back")
                    .isEqualTo("Team Alpha vs Team Beta");
        }
    }

    /**
     * The lists 0.0.x answered an outage with stay as they were, under either strategy: no market
     * descriptions, no sports and no active tournaments, and no market description by id, rather
     * than an exception or null.
     */
    @Test
    void theListsAnOutageEmptiesAreEmptyUnderEitherStrategy() {
        for (var strategy : ExceptionHandlingStrategy.values()) {
            try (FakeRestServer rest = FakeRestServer.start();
                    Sdk sdk = Sdk.withoutFeed(rest, builder -> builder.setExceptionHandlingStrategy(strategy))) {
                // 0.0.x hands out its managers only once it has the bookmaker details
                var markets = sdk.oddsFeed().getMarketDescriptionManager();
                var sportsInfo = sdk.oddsFeed().getSportsInfoManager();
                rest.startOutage(503);

                assertThat(markets.getMarketDescriptions())
                        .as(strategy + ": the market descriptions")
                        .isNotNull()
                        .isEmpty();
                assertThat(markets.getMarketDescriptions(Locale.ENGLISH))
                        .as(strategy + ": the market descriptions in English")
                        .isNotNull()
                        .isEmpty();
                assertThat(markets.getMarketDescription(1, null, Locale.ENGLISH))
                        .as(strategy + ": market 1")
                        .isNull();
                assertThat(sportsInfo.getSports())
                        .as(strategy + ": the sports")
                        .isNotNull()
                        .isEmpty();
                assertThat(sportsInfo.getActiveTournaments())
                        .as(strategy + ": the active tournaments")
                        .isNotNull()
                        .isEmpty();
                assertThat(sportsInfo.getActiveTournaments("Counter-Strike 2"))
                        .as(strategy + ": the active tournaments of a sport")
                        .isNotNull()
                        .isEmpty();
                rest.endOutage();
                rest.awaitQuiet();
            }
        }
    }

    /**
     * A recovery request the API refuses leaves the producer down. 0.0.x marks the recovery as
     * started anyway and asks again only five minutes after the request (0.0.57 and older once the
     * maximum recovery time had passed, six hours by default), so the alives that arrive after the
     * API is back change nothing for now. 1.0 re-issues it with backoff: a new request, with a new
     * request id. The API stays down through the HTTP client's own retries of the first call, which
     * carry its id, so only the re-issue can be the request that follows.
     */
    @Test
    void aRecoveryRequestTheApiRefusedIsAskedForAgain() throws InterruptedException {
        try (FakeRestServer rest = FakeRestServer.start();
                FakeFeed feed = FakeFeed.start();
                Sdk sdk = Sdk.against(rest, feed)) {
            sdk.open(MessageInterest.ALL);
            rest.startOutage(503);
            feed.publish(alive(1, true));
            String refused = rest.awaitRequest("POST", PREMATCH_RECOVERY).parameter("request_id");
            assertThat(refused).as("request id of the refused recovery").isNotBlank();

            KnownDifference.FAILED_RECOVERY_IS_NOT_RETRIED.expect(
                    () -> {
                        rest.endOutage();
                        assertThat(recoveriesWhileAlive(rest, feed, Duration.ofSeconds(3)))
                                .as("recovery requests of producer 1, with an alive every second after the outage")
                                .isEqualTo(1);
                    },
                    () -> {
                        // the call's own attempts, all of them refused: one request, sent again
                        assertThat(rest.awaitRequests("POST", PREMATCH_RECOVERY, 3))
                                .as("the HTTP client's attempts of the refused recovery")
                                .extracting(request -> request.parameter("request_id"))
                                .containsOnly(refused);
                        // ended only once the client has had every one of those refusals
                        rest.awaitOutageAnswers("POST", PREMATCH_RECOVERY, 3);
                        rest.endOutage();
                        // the backoff is the recovery actor's to choose; it has to fit in this wait
                        assertThat(reissueWhileAlive(rest, feed, refused, Duration.ofSeconds(30)))
                                .as("a recovery request of producer 1 after the refused one's, with an alive"
                                        + " every second after the outage")
                                .hasValueSatisfying(reissue -> assertThat(reissue.parameter("request_id"))
                                        .as("its request id")
                                        .isNotBlank()
                                        .isNotEqualTo(refused));
                    });
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

    /**
     * The first recovery request of producer 1 with a request id other than {@code refused}, sending
     * it an alive every second for up to {@code wait}; empty when none arrives.
     */
    private static Optional<RecordedRequest> reissueWhileAlive(
            FakeRestServer rest, FakeFeed feed, String refused, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            Optional<RecordedRequest> reissue = rest.requests("POST", PREMATCH_RECOVERY).stream()
                    .filter(request -> !refused.equals(request.parameter("request_id")))
                    .findFirst();
            if (reissue.isPresent() || System.nanoTime() > deadline) {
                return reissue;
            }
            feed.publish(alive(1, true));
            Thread.sleep(Duration.ofSeconds(1));
        }
    }
}
