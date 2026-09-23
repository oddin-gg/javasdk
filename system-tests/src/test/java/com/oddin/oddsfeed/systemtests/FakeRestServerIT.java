package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.fake.RecordedRequest;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.GlobalEventsListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The fake REST server, driven through the SDK's public API. This is the old 0.0.x SDK today;
 * the same calls will run against 1.0.
 *
 * <p>No message broker is involved: the SDK fetches the bookmaker details, producers and
 * entities over REST, and only opening a session connects to the feed.
 */
class FakeRestServerIT {

  private static final String TOKEN = "system-test-token";
  private static final String MATCH = "od:match:198314";

  private static final GlobalEventsListener NO_EVENTS = new GlobalEventsListener() {
    @Override
    public void onProducerStatusChange(ProducerStatus producerStatus) {}

    @Override
    public void onConnectionDown() {}

    @Override
    public void onEventRecoveryCompleted(URN eventId, long requestId) {}
  };

  @Test
  void theSdkReadsWhoamiProducersAndAMatchSummaryFromTheFake() {
    try (FakeRestServer fake = FakeRestServer.start()) {
      OddsFeed feed = sdkAgainst(fake);
      try {
        assertThat(feed.getBookMakerDetail().getBookmakerId()).isEqualTo(53);
        assertThat(feed.getProducerManager().getAvailableProducers()).containsKeys(1L, 2L);
        assertThat(feed.getSportsInfoManager()
            .getMatch(URN.parse(MATCH), Locale.ENGLISH)
            .getName(Locale.ENGLISH))
            .isEqualTo("Team Alpha vs Team Beta");
      } finally {
        fake.awaitQuiet();
        feed.close();
      }

      assertThat(fake.requests()).extracting(RecordedRequest::path).contains(
          "/v1/users/whoami",
          "/v1/descriptions/producers",
          "/v1/sports/en/sport_events/" + MATCH + "/summary");
      assertThat(fake.requests())
          .as("every call carries the access token")
          .allSatisfy(request -> assertThat(request.header("x-access-token")).isEqualTo(TOKEN));
    }
  }

  @Test
  void anOverrideReachesTheSdk() {
    try (FakeRestServer fake = FakeRestServer.start()) {
      fake.respond("/v1/users/whoami", 403, """
          <?xml version="1.0" encoding="UTF-8"?>
          <response response_code="FORBIDDEN"><action>whoami</action><message>no access</message></response>
          """);
      OddsFeed feed = sdkAgainst(fake);

      // Not closed afterwards: init stopped at the refused call, before anything was created that
      // would need releasing, and closing it would only log the SDK's own complaint about that.
      assertThatThrownBy(feed::getBookMakerDetail).hasMessageContaining("Failed to init odds feed");

      // the SDK stops at the refused call instead of going on to the producers
      assertThat(fake.requests()).extracting(RecordedRequest::path)
          .contains("/v1/users/whoami")
          .doesNotContain("/v1/descriptions/producers");
    }
  }

  @Test
  void everyRouteAnswersThePathTheSdkBuilds() throws Exception {
    // the paths as the SDK's API client builds them; a route that stops matching one would
    // quietly turn a system test's data into a 404
    List<String> paths = List.of(
        "/v1/users/whoami",
        "/v1/descriptions/producers",
        "/v1/descriptions/void_reasons",
        "/v1/descriptions/en/markets",
        "/v1/descriptions/en/match_status",
        "/v1/sports/en/sports",
        "/v1/sports/en/sports/od:sport:1/tournaments",
        "/v1/sports/en/tournaments/od:tournament:1/info",
        "/v1/sports/en/sport_events/od:match:1/summary",
        "/v1/sports/en/sport_events/od:match:1/fixture",
        "/v1/sports/en/fixtures/changes",
        "/v1/sports/en/schedules/live/schedule",
        "/v1/sports/en/schedules/pre/schedule",
        "/v1/sports/en/schedules/2026-09-23/schedule",
        "/v1/sports/en/competitors/od:competitor:1/profile",
        "/v1/sports/en/players/od:player:1/profile",
        "/v1/replay");

    try (FakeRestServer fake = FakeRestServer.start(); HttpClient http = HttpClient.newHttpClient()) {
      for (String path : paths) {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("https://" + fake.apiHost() + path)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertThat(response.body()).as(path).startsWith("<?xml");
      }

      HttpResponse<String> unknown = http.send(
          HttpRequest.newBuilder(URI.create("https://" + fake.apiHost() + "/v1/nothing/here")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(unknown.statusCode()).as("a path without a route").isEqualTo(404);
    }
  }

  private static OddsFeed sdkAgainst(FakeRestServer fake) {
    OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
        .selectEnvironment("127.0.0.1", fake.apiHost())
        .setAccessToken(TOKEN)
        .build();
    return new OddsFeed(NO_EVENTS, configuration);
  }
}
