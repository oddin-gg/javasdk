package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.LogCapture;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.exceptions.OddsFeedSdkException;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import org.junit.jupiter.api.Test;

/**
 * Opening the feed when something it needs refuses: the REST API down, the API or the broker
 * turning the access token away. Opening fails with an exception, nothing is half started, and a
 * new feed opens once the cause is gone.
 *
 * <p>Which call fails differs between the lines - 0.0.x fetches the bookmaker details as soon as a
 * session builder is asked for, 1.0 may leave it to {@code open()} - so each scenario treats
 * building the session and opening as one step.
 */
class StartupScenarioIT {

  private static final String ODDS_CHANGE = "feed/odds_change/odds_change_markets_only.xml";
  private static final String WHOAMI = "/v1/users/whoami";

  /**
   * With the API down the feed does not start and never connects to the broker. A new feed
   * started once the API is back works. 0.0.x logs an error when the failed feed is closed: it
   * builds its timer only on close, and that needs the bookmaker details it never got.
   */
  @Test
  void withTheApiDownTheFeedDoesNotStart() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start()) {
      rest.startOutage(503);
      try (LogCapture logs = LogCapture.start();
          Sdk failed = Sdk.against(rest, feed)) {
        assertThat(catchThrowable(() -> failed.open(MessageInterest.ALL)))
            .as("opening the feed while the API is down").isInstanceOf(OddsFeedSdkException.class);
        assertThat(feed.logins()).as("logins to the broker").isEmpty();

        failed.close();
        KnownDifference.CLOSE_AFTER_A_FAILED_START_LOGS_AN_ERROR.expect(
            () -> assertThat(logs.warningsFrom("com.oddin")).as("what closing the failed feed logged")
                .anySatisfy(line -> assertThat(line).startsWith("ERROR").contains("Failed to close")),
            () -> assertThat(logs.warningsFrom("com.oddin")).as("what closing the failed feed logged")
                .noneSatisfy(line -> assertThat(line).startsWith("ERROR")));
      }

      rest.endOutage();
      try (Sdk sdk = Sdk.against(rest, feed)) {
        Received received = sdk.open(MessageInterest.ALL);
        feed.publishFixture(ODDS_CHANGE);
        assertThat(received.next(OddsChange.class)).as("an odds change on a new feed").isNotNull();
      }
    }
  }

  /**
   * The bookmaker details load but the producer list does not: the feed still does not start, and
   * nothing is connected.
   */
  @Test
  void withoutTheProducerListTheFeedDoesNotStart() {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      rest.respond("/v1/descriptions/producers", 503, "");

      assertThat(catchThrowable(() -> sdk.open(MessageInterest.ALL)))
          .as("opening the feed without a producer list").isInstanceOf(OddsFeedSdkException.class);
      assertThat(feed.logins()).as("logins to the broker").isEmpty();
    }
  }

  /**
   * The API refuses the access token: the feed does not start, asked once - a refused token is
   * not worth retrying - and with the token it was given.
   */
  @Test
  void aTokenTheApiRefusesStopsTheStart() {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start()) {
      rest.respond(WHOAMI, 401, """
          <?xml version="1.0" encoding="UTF-8"?>
          <response response_code="FORBIDDEN">
              <action>whoami</action>
              <message>invalid access token</message>
          </response>
          """);
      // not closed: 0.0.x would only log its complaint about the missing bookmaker details, which
      // withTheApiDownTheFeedDoesNotStart checks already
      var sdk = Sdk.against(rest, feed);

      assertThat(catchThrowable(() -> sdk.open(MessageInterest.ALL)))
          .as("opening the feed with a refused token").isInstanceOf(OddsFeedSdkException.class);
      assertThat(rest.requests("GET", WHOAMI)).as("bookmaker details requests").hasSize(1)
          .allSatisfy(request -> assertThat(request.header("x-access-token")).isEqualTo(Sdk.TOKEN));
      assertThat(feed.logins()).as("logins to the broker").isEmpty();
    }
  }

  /**
   * The API accepts the token, the broker does not: opening fails, after the SDK tried to log in
   * with the token, and no connection is left open. 0.0.x tries once and lets the AMQP client's
   * own exception out of {@code open()}.
   */
  @Test
  void aTokenTheBrokerRefusesStopsTheStart() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      feed.refuseLogins();

      var failure = catchThrowable(() -> sdk.open(MessageInterest.ALL));
      assertThat(failure).as("opening the feed with a token the broker refuses").isNotNull();
      assertThat(feed.refusedLogins()).as("refused logins").isNotEmpty().containsOnly(Sdk.TOKEN);
      assertThat(feed.openConnections()).as("connections on the broker").isEmpty();
      KnownDifference.REFUSED_LOGIN_ESCAPES_AS_A_BROKER_EXCEPTION.expectLegacy(() -> {
        assertThat(failure).as("what open() threw")
            .isNotInstanceOf(OddsFeedSdkException.class)
            .hasMessageContaining("ACCESS_REFUSED");
        assertThat(feed.refusedLogins()).as("refused logins, one try").hasSize(1);
      });
    }
  }
}
