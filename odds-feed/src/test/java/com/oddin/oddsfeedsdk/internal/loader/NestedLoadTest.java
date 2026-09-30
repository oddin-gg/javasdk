package com.oddin.oddsfeedsdk.internal.loader;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Loads inside loads, over the real REST client with a single entity permit: a match load that
 * then loads its competitors. The permit is held for one HTTP call only, so the nested loads get it
 * while their parents wait; with a permit held across the fan-out, every parent would hold one and
 * wait for children that never get one.
 */
class NestedLoadTest {

    @Test
    void nestedLoadsFinishWithOnePermitBetweenThem() throws Exception {
        try (FakeRestServer api = FakeRestServer.start();
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            OddsFeedConfiguration configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                    .selectEnvironment("mq.invalid", api.apiHost())
                    .setAccessToken("token")
                    .setRestConcurrencyLimit(1)
                    .setHttpClientTimeout(Duration.ofSeconds(10))
                    .build();
            for (int m = 0; m < 4; m++) {
                api.respond(
                        "/v1/sports/en/sport_events/od:match:" + m + "/summary",
                        Reply.of(200, Fixtures.read("rest/match_summary/match_summary.xml"))
                                .after(Duration.ofMillis(50)));
            }
            try (var client = new ApiClient(configuration, ApiEvents.NONE)) {
                Duration deadline = configuration.getHttpClientTimeout();
                var competitors = new Loader<URN, String>(
                        "competitor",
                        id -> client.fetchCompetitorProfile(id, Locale.ENGLISH)
                                .getCompetitor()
                                .getName(),
                        deadline,
                        Duration.ofSeconds(1),
                        threads);
                var matches = new Loader<URN, List<String>>(
                        "match",
                        id -> {
                            var summary = client.fetchMatchSummary(id, Locale.ENGLISH);
                            var names = new ArrayList<String>();
                            for (var competitor :
                                    summary.getSportEvent().getCompetitors().getCompetitor()) {
                                names.add(competitors.load(URN.parse(competitor.getId())));
                            }
                            return names;
                        },
                        deadline,
                        Duration.ofSeconds(1),
                        threads);

                List<Future<List<String>>> loads = new ArrayList<>();
                for (int m = 0; m < 4; m++) {
                    URN match = URN.parse("od:match:" + m);
                    loads.add(threads.submit(() -> matches.load(match)));
                }
                for (Future<List<String>> load : loads) {
                    assertThat(load.get(15, TimeUnit.SECONDS)).isNotEmpty();
                }
            }
            assertThat(api.mostInFlight()).as("one permit: one call at a time").isEqualTo(1);
        }
    }
}
