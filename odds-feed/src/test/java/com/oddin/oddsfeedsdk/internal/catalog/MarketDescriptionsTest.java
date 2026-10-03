package com.oddin.oddsfeedsdk.internal.catalog;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.factories.OutcomeType;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket.Outcome;
import com.oddin.oddsfeedsdk.internal.catalog.LocalizedMarket.Specifier;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The market descriptions over the real REST client, against the fake API: the lists and the
 * variants fetched on their own, each refreshed on its own age. Background refreshes wait in a
 * queue until the test runs them.
 */
class MarketDescriptionsTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final String DYNAMIC = "od:dynamic_outcomes:770";
    private static final String LIST_EN = "/v1/descriptions/en/markets";
    private static final String VARIANT_EN = "/v1/descriptions/en/markets/768/variants/" + DYNAMIC;
    private static final String VARIANT_DE = "/v1/descriptions/de/markets/768/variants/" + DYNAMIC;
    private static final Duration PAST_REFRESH = MarketDescriptions.REFRESH_AGE.plusSeconds(1);

    private final FakeTime time = new FakeTime();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Runnable> queuedRefreshes = new ArrayList<>();
    private FakeRestServer api;
    private ApiClient client;
    private MarketDescriptions markets;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(Duration.ofSeconds(10))
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        markets = new MarketDescriptions(client, Duration.ofSeconds(10), threads, queuedRefreshes::add, time, time);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void aLocaleListIsFetchedOnceAndDescribesItsMarkets() {
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions.xml"));
        LocalizedMarket winner = requireNonNull(markets.market(1, null, EN));
        assertThat(winner.id()).isEqualTo(1);
        assertThat(winner.variant()).isNull();
        assertThat(winner.name()).isEqualTo("Winner");
        assertThat(winner.outcomes())
                .containsExactly(new Outcome("1", "Team Alpha", null), new Outcome("2", "Team Beta", null));
        assertThat(winner.specifiers()).containsExactly(new Specifier("variant", "variable_text"));
        assertThat(winner.groups()).containsExactly("all", "regular_play");
        assertThat(winner.includesOutcomesOfType()).isNull();
        assertThat(winner.type()).isNull();

        assertThat(markets.market(1, "", EN)).as("an empty variant is none").isEqualTo(winner);
        assertThat(markets.market(2, null, EN)).isNull();
        assertThat(markets.markets(EN))
                .extracting(LocalizedMarket::key)
                .containsExactly(MarketKey.of(1, null), MarketKey.of(768, DYNAMIC));
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);
        assertThat(api.requests("GET", VARIANT_EN)).isEmpty();
    }

    @Test
    void aFixedVariantIsTheListsOwnRow() {
        api.respond(
                LIST_EN,
                200,
                list(
                        market(5, "Handicap"),
                        "<market id=\"5\" name=\"Handicap, three way\" variant=\"way:three\">"
                                + "<outcomes><outcome id=\"1\" name=\"home\"/></outcomes></market>"));
        LocalizedMarket threeWay = requireNonNull(markets.market(5, "way:three", EN));
        assertThat(threeWay.name()).isEqualTo("Handicap, three way");
        assertThat(threeWay.variant()).isEqualTo("way:three");
        assertThat(threeWay.key()).isEqualTo(MarketKey.of(5, "way:three"));
        assertThat(requireNonNull(markets.market(5, null, EN)).name()).isEqualTo("Handicap");
        assertThat(markets.market(5, "way:two", EN)).isNull();
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);
        assertThat(api.requests().stream().filter(request -> request.path().contains("/variants/")))
                .as("not fetched on its own")
                .isEmpty();
    }

    @Test
    void aDynamicVariantIsFetchedOnItsOwnInEachLocale() {
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        LocalizedMarket market = requireNonNull(markets.market(768, DYNAMIC, EN));
        assertThat(market.variant()).isEqualTo(DYNAMIC);
        assertThat(market.outcomes()).extracting(Outcome::name).containsExactly("Player Two");
        assertThat(market.includesOutcomesOfType()).isEqualTo("od:player");
        assertThat(market.type()).isEqualTo(OutcomeType.PLAYER);
        markets.market(768, DYNAMIC, EN);
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(1);
        assertThat(api.requests("GET", LIST_EN))
                .as("the list does not describe it")
                .isEmpty();

        api.respond(VARIANT_DE, 200, variant(DYNAMIC, "Spieler Zwei"));
        assertThat(requireNonNull(markets.market(768, DYNAMIC, DE)).outcomes())
                .extracting(Outcome::name)
                .containsExactly("Spieler Zwei");
        assertThat(api.requests("GET", VARIANT_DE)).hasSize(1);
    }

    @Test
    void ofTheRowsOfTheVariantsMarketTheOneWithTheVariantAskedForIsTaken() {
        String other = "<market id=\"768\" name=\"Player to Score\" variant=\"od:dynamic_outcomes:1\">"
                + "<outcomes><outcome id=\"od:player:1\" name=\"Other Player\"/></outcomes></market>";
        String plain = "<market id=\"768\" name=\"Player to Score\">"
                + "<outcomes><outcome id=\"od:player:3\" name=\"No Variant\"/></outcomes></market>";
        String asked = "<market id=\"768\" name=\"Player to Score\" variant=\"" + DYNAMIC + "\">"
                + "<outcomes><outcome id=\"od:player:2\" name=\"Player Two\"/></outcomes></market>";
        api.respond(VARIANT_EN, 200, list(other, asked, plain));
        assertThat(requireNonNull(markets.market(768, DYNAMIC, EN)).outcomes())
                .extracting(Outcome::name)
                .containsExactly("Player Two");
        api.respond(VARIANT_DE, 200, list(asked, other));
        assertThat(requireNonNull(markets.market(768, DYNAMIC, DE)).outcomes())
                .extracting(Outcome::name)
                .containsExactly("Player Two");
        api.respond("/v1/descriptions/en/markets/768/variants/od:dynamic_outcomes:9", 200, list(plain, other));
        assertThat(requireNonNull(markets.market(768, "od:dynamic_outcomes:9", EN))
                        .outcomes())
                .extracting(Outcome::name)
                .as("none has it: the first row of the market")
                .containsExactly("No Variant");
    }

    @Test
    void aVariantAnswerWithoutItsMarketFailsTheReadAlsoWhileItBacksOff() {
        api.respond(VARIANT_EN, 200, list("<market id=\"7\" name=\"Other\"><outcomes/></market>"));
        assertThatThrownBy(() -> markets.market(768, DYNAMIC, EN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not describe market 768");
        assertThatThrownBy(() -> markets.market(768, DYNAMIC, EN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not fetched again before");
        assertThat(api.requests("GET", VARIANT_EN)).as("backing off").hasSize(1);
    }

    @Test
    void aVariantIsKeptUnderTheVariantItWasAskedForWhateverTheAnswerSays() {
        api.respond(
                VARIANT_EN,
                200,
                list("<market id=\"7\" name=\"Other\"><outcomes/></market>"
                        + "<market id=\"768\" name=\"Player to Score\"><outcomes/></market>"));
        LocalizedMarket market = requireNonNull(markets.market(768, DYNAMIC, EN));
        assertThat(market.id()).isEqualTo(768);
        assertThat(market.variant()).isEqualTo(DYNAMIC);
        assertThat(market.key()).isEqualTo(MarketKey.of(768, DYNAMIC));
    }

    @Test
    void aVariantTheApiDoesNotKnowIsNullAndIsNotAskedForAgainAtOnce() {
        api.respond(VARIANT_EN, 404, Fixtures.read("rest/error/not_found.xml"));
        assertThat(markets.market(768, DYNAMIC, EN)).isNull();
        assertThat(markets.market(768, DYNAMIC, EN)).isNull();
        assertThat(api.requests("GET", VARIANT_EN)).as("backing off").hasSize(1);
        time.advance(Catalog.FIRST_BACKOFF.plusMillis(1));
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        assertThat(markets.market(768, DYNAMIC, EN)).isNotNull();
    }

    @Test
    void aVariantThatFailsOtherwiseFailsTheReadAlsoWhileItBacksOff() {
        api.respond(VARIANT_EN, 500, "");
        assertThatThrownBy(() -> markets.market(768, DYNAMIC, EN)).isInstanceOf(ApiException.class);
        int asked = api.requests("GET", VARIANT_EN).size();
        assertThatThrownBy(() -> markets.market(768, DYNAMIC, EN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not fetched again before");
        assertThat(api.requests("GET", VARIANT_EN)).as("backing off").hasSize(asked);
    }

    @Test
    void aListRefreshReplacesTheListedMarketsAndLeavesTheVariantsAlone() {
        api.respond(LIST_EN, 200, list(market(1, "Winner"), market(2, "Total")));
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        markets.market(1, null, EN);
        time.advance(Duration.ofMinutes(30));
        markets.market(768, DYNAMIC, EN);

        api.respond(LIST_EN, 200, list(market(1, "Match Winner")));
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Three"));
        time.advance(Duration.ofMinutes(31));
        assertThat(requireNonNull(markets.market(1, null, EN)).name())
                .as("served stale")
                .isEqualTo("Winner");
        runRefreshes();
        assertThat(requireNonNull(markets.market(1, null, EN)).name()).isEqualTo("Match Winner");
        assertThat(markets.market(2, null, EN)).as("removed upstream").isNull();
        assertThat(requireNonNull(markets.market(768, DYNAMIC, EN)).outcomes())
                .extracting(Outcome::name)
                .as("the variant is younger than its refresh age")
                .containsExactly("Player Two");
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(1);
        assertThat(api.requests("GET", LIST_EN)).hasSize(2);
    }

    @Test
    void aVariantRefreshesOnItsOwnAge() {
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        markets.market(768, DYNAMIC, EN);
        time.advance(Duration.ofMinutes(30));
        markets.market(1, null, EN);

        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Three"));
        time.advance(Duration.ofMinutes(31));
        markets.market(1, null, EN);
        assertThat(requireNonNull(markets.market(768, DYNAMIC, EN)).outcomes())
                .extracting(Outcome::name)
                .as("served stale")
                .containsExactly("Player Two");
        assertThat(queuedRefreshes).as("the variant's refresh, not the list's").hasSize(1);
        runRefreshes();
        assertThat(requireNonNull(markets.market(768, DYNAMIC, EN)).outcomes())
                .extracting(Outcome::name)
                .containsExactly("Player Three");
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);
    }

    @Test
    void aMarketNewUpstreamShowsUpBeforeTheNextRefresh() {
        api.respond(LIST_EN, 200, list(market(1, "Winner")));
        markets.market(1, null, EN);
        api.respond(LIST_EN, 200, list(market(1, "Winner"), market(3, "Handicap")));
        time.advance(Duration.ofMinutes(2));
        assertThat(requireNonNull(markets.market(3, null, EN)).name()).isEqualTo("Handicap");
        assertThat(markets.market(99, null, EN)).isNull();
        assertThat(api.requests("GET", LIST_EN)).as("the list just came").hasSize(2);
    }

    @Test
    void anEmptyListDoesNotReplaceOneWithMarketsInIt() {
        api.respond(LIST_EN, 200, list(market(1, "Winner")));
        markets.market(1, null, EN);
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions_empty.xml"));
        time.advance(PAST_REFRESH);
        markets.market(1, null, EN);
        runRefreshes();
        assertThat(requireNonNull(markets.market(1, null, EN)).name()).isEqualTo("Winner");
        CatalogHealth lists = markets.health().getFirst();
        assertThat(lists.name()).isEqualTo("market descriptions");
        assertThat(lists.failedFetches()).isEqualTo(1);
        assertThat(lists.servedStale()).isEqualTo(2);

        api.respond("/v1/descriptions/de/markets", 200, Fixtures.read("rest/markets/market_descriptions_empty.xml"));
        assertThat(markets.markets(DE)).as("an empty list replaces nothing").isEmpty();
    }

    @Test
    void theListingHasTheVariantsFetchedInItsLocale() {
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions.xml"));
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        api.respond(
                "/v1/descriptions/de/markets/768/variants/od:dynamic_outcomes:1",
                200,
                variant("od:dynamic_outcomes:1", "Spieler"));
        markets.market(768, DYNAMIC, EN);
        markets.market(768, "od:dynamic_outcomes:1", DE);

        assertThat(markets.markets(EN))
                .extracting(LocalizedMarket::key)
                .containsExactly(MarketKey.of(1, null), MarketKey.of(768, DYNAMIC));
        assertThat(markets.markets(EN).get(1).outcomes())
                .extracting(Outcome::name)
                .as("its own, not the list's")
                .containsExactly("Player Two");
    }

    @Test
    void clearingAVariantDropsItInEveryLocaleAndClearingAListedMarketDropsTheLists() {
        api.respond(VARIANT_EN, 200, variant(DYNAMIC, "Player Two"));
        api.respond(VARIANT_DE, 200, variant(DYNAMIC, "Spieler Zwei"));
        markets.market(768, DYNAMIC, EN);
        markets.market(768, DYNAMIC, DE);
        markets.market(1, null, EN);

        markets.clear(768, DYNAMIC);
        markets.market(768, DYNAMIC, EN);
        markets.market(768, DYNAMIC, DE);
        markets.market(1, null, EN);
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(2);
        assertThat(api.requests("GET", VARIANT_DE)).hasSize(2);
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);

        markets.clear(1, null);
        markets.market(1, null, EN);
        markets.market(768, DYNAMIC, EN);
        assertThat(api.requests("GET", LIST_EN)).hasSize(2);
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(2);

        markets.clear();
        markets.market(1, null, EN);
        markets.market(768, DYNAMIC, EN);
        assertThat(api.requests("GET", LIST_EN)).hasSize(3);
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(3);
    }

    private void runRefreshes() {
        var queued = List.copyOf(queuedRefreshes);
        queuedRefreshes.clear();
        queued.forEach(Runnable::run);
    }

    private static String market(int id, String name) {
        return "<market id=\"" + id + "\" name=\"" + name + "\"><outcomes><outcome id=\"1\" name=\"one\"/></outcomes>"
                + "</market>";
    }

    private static String variant(String variant, String player) {
        return list("<market id=\"768\" name=\"Player to Score\" variant=\"" + variant + "\""
                + " includes_outcomes_of_type=\"od:player\" outcome_type=\"player\">"
                + "<outcomes><outcome id=\"od:player:2\" name=\"" + player + "\"/></outcomes></market>");
    }

    private static String list(String... markets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><market_descriptions response_code=\"OK\">"
                + String.join("", markets) + "</market_descriptions>";
    }
}
