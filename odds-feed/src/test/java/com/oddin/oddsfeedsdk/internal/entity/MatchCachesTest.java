package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.sportevent.EventStatus;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.internal.cache.LiveState.LiveValues;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The match and fixture caches over the real REST client, against the fake API. */
class MatchCachesTest {

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final String SUMMARY_EN = "/v1/sports/en/sport_events/od:match:198314/summary";
    private static final String SUMMARY_DE = "/v1/sports/de/sport_events/od:match:198314/summary";
    private static final String FIXTURE_EN = "/v1/sports/en/sport_events/od:match:198314/fixture";
    private static final String SUMMARY = Fixtures.read("rest/match_summary/match_summary.xml");

    private final FakeTime time = new FakeTime();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private FakeRestServer api;
    private ApiClient client;
    private MatchCaches caches;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(Duration.ofSeconds(10))
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        caches = new MatchCaches(client, Duration.ofSeconds(10), Locale.ENGLISH, threads, time, time);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void aMatchIsLoadedOncePerLocaleWithItsSharedFieldsWrittenByEach() {
        api.respond(SUMMARY_EN, 200, SUMMARY);
        api.respond(SUMMARY_DE, 200, SUMMARY.replace("Team Alpha vs Team Beta", "Mannschaft Alpha gegen Beta"));

        Entry english = caches.match(MATCH, Locale.ENGLISH);
        assertThat(english.get(NAME, Locale.ENGLISH)).isEqualTo("Team Alpha vs Team Beta");
        caches.match(MATCH, Locale.ENGLISH);
        assertThat(api.requests("GET", SUMMARY_EN))
                .as("fresh: not loaded again")
                .hasSize(1);

        Entry both = caches.match(MATCH, Locale.GERMAN);
        assertThat(api.requests("GET", SUMMARY_DE)).hasSize(1);
        assertThat(both.get(NAME, Locale.GERMAN)).isEqualTo("Mannschaft Alpha gegen Beta");
        assertThat(both.get(NAME, Locale.ENGLISH)).isEqualTo("Team Alpha vs Team Beta");
        assertThat(both.get(TOURNAMENT_ID, null)).isEqualTo(URN.parse("od:tournament:1042"));

        time.advance(MatchCaches.AGE.plusMinutes(1));
        caches.match(MATCH, Locale.ENGLISH);
        assertThat(api.requests("GET", SUMMARY_EN))
                .as("out of date: loaded again")
                .hasSize(2);
    }

    @Test
    void readersAtOnceShareOneLoad() throws Exception {
        api.respond(SUMMARY_EN, Reply.of(200, SUMMARY).after(Duration.ofMillis(300)));
        List<Future<Entry>> readers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            readers.add(threads.submit(() -> caches.match(MATCH, Locale.ENGLISH)));
        }
        for (Future<Entry> reader : readers) {
            assertThat(reader.get(10, TimeUnit.SECONDS).get(NAME, Locale.ENGLISH))
                    .isEqualTo("Team Alpha vs Team Beta");
        }
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(1);
    }

    @Test
    void aFixtureChangeInvalidatesTheMatchAndItsFixture() {
        api.respond(
                SUMMARY_EN,
                Reply.of(200, SUMMARY),
                Reply.of(200, SUMMARY.replace("Team Alpha vs Team Beta", "Team Alpha vs Team Gamma")));
        api.respond(FIXTURE_EN, 200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"));
        caches.match(MATCH, Locale.ENGLISH);
        caches.fixture(MATCH);

        caches.fixtureChange(MATCH);
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(NAME, Locale.ENGLISH))
                .isEqualTo("Team Alpha vs Team Gamma");
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(2);
        caches.fixture(MATCH);
        assertThat(api.requests("GET", FIXTURE_EN)).hasSize(2);
    }

    @Test
    void theFeedOwnsTheLiveStatusFromKickOffUntilItGoesQuiet() {
        api.respond(
                SUMMARY_EN,
                Reply.of(200, SUMMARY.replace("status=\"closed\" scoreboard", "status=\"not_started\" scoreboard")),
                Reply.of(200, SUMMARY));
        assertThat(statusOf(caches.live(MATCH))).as("loaded before kick-off").isEqualTo(EventStatus.NotStarted);

        var kickOff = new OFSportEventStatus();
        kickOff.setStatus(OFEventStatus.LIVE);
        assertThat(caches.oddsChange(MATCH, 1, 1_000, Duration.ZERO, time.instant(), kickOff))
                .isTrue();
        time.advance(Duration.ofMinutes(19));
        assertThat(statusOf(caches.live(MATCH))).as("the feed's, still fresh").isEqualTo(EventStatus.Live);
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(1);

        time.advance(Duration.ofMinutes(2));
        assertThat(statusOf(caches.live(MATCH)))
                .as("quiet for the match status age: the summary is loaded again, and taken")
                .isEqualTo(EventStatus.Finished);
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(2);
    }

    @Test
    void aSummaryLoadedWhileTheFeedIsLiveLeavesTheLiveStatusAlone() {
        api.respond(SUMMARY_EN, 200, SUMMARY);
        var live = new OFSportEventStatus();
        live.setStatus(OFEventStatus.LIVE);
        caches.oddsChange(MATCH, 1, 1_000, Duration.ZERO, time.instant(), live);
        caches.match(MATCH, Locale.ENGLISH);
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(1);
        assertThat(statusOf(caches.live(MATCH))).isEqualTo(EventStatus.Live);
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(WINNER_ID, null))
                .as("the summary's own fields are written")
                .isEqualTo(URN.parse("od:competitor:47214"));
    }

    @Test
    void aFixtureIsLoadedInTheDefaultLocaleAndOnlyFillsTheMatch() {
        api.respond(FIXTURE_EN, 200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"));
        api.respond(
                SUMMARY_EN,
                200,
                SUMMARY.replace(
                        "scheduled=\"2026-08-26T18:00:00\" status", "scheduled=\"2026-08-26T19:00:00\" status"));
        Entry fixture = caches.fixture(MATCH);
        assertThat(fixture.get(START_TIME, null)).isEqualTo(Instant.parse("2026-08-26T18:00:00Z"));
        caches.fixture(MATCH);
        assertThat(api.requests("GET", FIXTURE_EN)).hasSize(1);

        Entry filled = caches.match(MATCH, Locale.ENGLISH);
        assertThat(api.requests("GET", SUMMARY_EN))
                .as("a fill marks no locale loaded")
                .hasSize(1);
        assertThat(filled.get(SCHEDULED, null))
                .as("the summary's, over the fixture's fill")
                .isEqualTo(Instant.parse("2026-08-26T19:00:00Z"));
    }

    @Test
    void aScheduleFetchedBeforeAFixtureChangeFillsNothingOfThatMatch() throws Exception {
        var schedule = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES)
                .decode(Fixtures.read("rest/schedule/schedule.xml").getBytes(UTF_8), RAScheduleEndpoint.class);
        var other = URN.parse("od:match:198315");

        var before = caches.startMany(() -> false);
        caches.fixtureChange(MATCH);
        caches.fill(schedule.getSportEvent(), Locale.ENGLISH, before);
        assertThat(caches.cachedMatch(MATCH))
                .as("changed since the schedule was fetched")
                .isNull();
        assertThat(requireNonNull(caches.cachedMatch(other)).get(NAME, Locale.ENGLISH))
                .as("the other match listed")
                .isEqualTo("Team Gamma vs Team Delta");

        // at once: an invalidation from before the fetch started is not news to it
        caches.fill(schedule.getSportEvent(), Locale.ENGLISH, caches.startMany(() -> false));
        assertThat(requireNonNull(caches.cachedMatch(MATCH)).get(NAME, Locale.ENGLISH))
                .as("a schedule fetched after the change fills it")
                .isEqualTo("Team Alpha vs Team Beta");
        assertThat(api.requests()).as("a fill loads nothing").isEmpty();
    }

    @Test
    void aSummaryFetchedBeforeAFixtureChangeWritesNeitherTheMatchNorItsLiveState() throws Exception {
        api.respond(SUMMARY_EN, Reply.of(200, SUMMARY).after(Duration.ofMillis(500)), Reply.of(500, "<error/>"));
        Future<Entry> reading = threads.submit(() -> caches.match(MATCH, Locale.ENGLISH));
        api.awaitRequest("GET", SUMMARY_EN);
        caches.fixtureChange(MATCH);
        assertThatThrownBy(() -> reading.get(10, TimeUnit.SECONDS))
                .as("asked again after the change, and the API failed")
                .hasCauseInstanceOf(com.oddin.oddsfeedsdk.exceptions.ApiException.class);
        assertThat(api.requests("GET", SUMMARY_EN)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(caches.cachedMatch(MATCH))
                .as("the tombstone only")
                .satisfiesAnyOf(
                        entry -> assertThat(entry).isNull(),
                        entry -> assertThat(requireNonNull(entry).get(NAME, Locale.ENGLISH))
                                .isNull());
        assertThat(caches.cachedLive(MATCH)).as("nor its live status").isNull();
    }

    @Test
    void aReadAfterAFixtureChangeLoadsTheChangedMatchThoughALoadFromBeforeWasUnderWay() throws Exception {
        api.respond(
                SUMMARY_EN,
                Reply.of(200, SUMMARY).after(Duration.ofMillis(500)),
                Reply.of(200, SUMMARY.replace("Team Alpha vs Team Beta", "Team Alpha vs Team Gamma")));
        Future<Entry> before = threads.submit(() -> caches.match(MATCH, Locale.ENGLISH));
        api.awaitRequest("GET", SUMMARY_EN);
        caches.fixtureChange(MATCH);
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(NAME, Locale.ENGLISH))
                .isEqualTo("Team Alpha vs Team Gamma");
        assertThat(before.get(10, TimeUnit.SECONDS).get(NAME, Locale.ENGLISH)).isEqualTo("Team Alpha vs Team Gamma");
    }

    @Test
    void aFixtureFetchedBeforeAFixtureChangeWritesNothing() throws Exception {
        api.respond(
                FIXTURE_EN,
                Reply.of(200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"))
                        .after(Duration.ofMillis(500)),
                Reply.of(500, "<error/>"));
        Future<Entry> reading = threads.submit(() -> caches.fixture(MATCH));
        api.awaitRequest("GET", FIXTURE_EN);
        caches.fixtureChange(MATCH);
        assertThatThrownBy(() -> reading.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(com.oddin.oddsfeedsdk.exceptions.ApiException.class);
        Entry fixture = caches.cachedFixture(MATCH);
        assertThat(fixture == null || fixture.get(START_TIME, null) == null).isTrue();
        Entry match = caches.cachedMatch(MATCH);
        assertThat(match == null || match.get(NAME, Locale.ENGLISH) == null)
                .as("nor its fill of the match")
                .isTrue();
    }

    @Test
    void aSummaryWithoutAStatusKeepsTheWinnerAndIsNotAskedAgainForTheLiveState() {
        String withoutStatus = SUMMARY.substring(0, SUMMARY.indexOf("    <sport_event_status")) + "</match_summary>\n";
        api.respond(SUMMARY_EN, 200, SUMMARY);
        api.respond(SUMMARY_DE, 200, withoutStatus);
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(WINNER_ID, null)).isNotNull();
        // the newer summary, in another locale, has no status at all
        Entry match = caches.match(MATCH, Locale.GERMAN);
        assertThat(match.get(NAME, Locale.GERMAN)).isEqualTo("Team Alpha vs Team Beta");
        assertThat(match.get(WINNER_ID, null))
                .as("no status: nothing said of the winner")
                .isNotNull();

        var other = URN.parse("od:match:198316");
        String otherSummary = "/v1/sports/en/sport_events/od:match:198316/summary";
        api.respond(otherSummary, 200, withoutStatus.replace("od:match:198314", "od:match:198316"));
        LiveValues live = caches.live(other);
        caches.live(other);
        assertThat(api.requests("GET", otherSummary))
                .as("REST was asked, and answered: not again")
                .hasSize(1);
        assertThat(requireNonNull(live).get(STATUS)).isNull();
    }

    @Test
    void aFixtureLoadedAfterTheSummaryLeavesItsFieldsAlone() {
        api.respond(
                SUMMARY_EN,
                200,
                SUMMARY.replace(
                        "scheduled=\"2026-08-26T18:00:00\" status", "scheduled=\"2026-08-26T19:00:00\" status"));
        api.respond(FIXTURE_EN, 200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"));
        caches.match(MATCH, Locale.ENGLISH);
        assertThat(caches.fixture(MATCH).get(START_TIME, null)).isEqualTo(Instant.parse("2026-08-26T18:00:00Z"));
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(SCHEDULED, null))
                .as("the summary's; the fixture only fills")
                .isEqualTo(Instant.parse("2026-08-26T19:00:00Z"));
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(EXTRA_INFO, null))
                .as("what the summary had not, the fixture filled")
                .containsEntry("sport_format", "esports");
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(1);
    }

    @Test
    void clearDropsMatchesAndFixturesButNotTheLiveState() {
        api.respond(SUMMARY_EN, 200, SUMMARY);
        api.respond(FIXTURE_EN, 200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"));
        caches.fixture(MATCH);
        caches.match(MATCH, Locale.ENGLISH);
        var live = new OFSportEventStatus();
        live.setStatus(OFEventStatus.LIVE);
        caches.oddsChange(MATCH, 1, 1_000, Duration.ZERO, time.instant(), live);
        caches.clear();
        caches.match(MATCH, Locale.ENGLISH);
        assertThat(api.requests("GET", SUMMARY_EN)).hasSize(2);
        caches.fixture(MATCH);
        assertThat(api.requests("GET", FIXTURE_EN)).hasSize(2);
        assertThat(statusOf(caches.live(MATCH))).isEqualTo(EventStatus.Live);
    }

    @Test
    void anInvalidationBetweenAReadsCheckAndItsReadDoesNotLeaveItEmpty() {
        api.respond(SUMMARY_EN, 200, SUMMARY);
        api.respond(FIXTURE_EN, 200, Fixtures.read("rest/fixtures_fixture/fixtures_fixture.xml"));
        caches.match(MATCH, Locale.ENGLISH);
        time.onNextInstant(() -> caches.fixtureChange(MATCH));
        assertThat(caches.match(MATCH, Locale.ENGLISH).get(NAME, Locale.ENGLISH))
                .as("the match as it was when the read looked")
                .isEqualTo("Team Alpha vs Team Beta");

        caches.fixture(MATCH);
        time.onNextInstant(caches::clear);
        assertThat(caches.fixture(MATCH).get(TV_CHANNELS, null)).hasSize(1);
    }

    private static EventStatus statusOf(@Nullable LiveValues values) {
        return requireNonNull(requireNonNull(values).get(STATUS));
    }
}
