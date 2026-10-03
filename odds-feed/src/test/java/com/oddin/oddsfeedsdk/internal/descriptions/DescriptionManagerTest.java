package com.oddin.oddsfeedsdk.internal.descriptions;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.FakeRestServer.Reply;
import com.oddin.oddsfeed.fakes.Fixtures;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.factories.MarketDescription;
import com.oddin.oddsfeedsdk.api.factories.MarketVoidReason;
import com.oddin.oddsfeedsdk.api.factories.OutcomeDescription;
import com.oddin.oddsfeedsdk.api.factories.OutcomeType;
import com.oddin.oddsfeedsdk.api.factories.Specifier;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.exceptions.ItemNotFoundException;
import com.oddin.oddsfeedsdk.internal.catalog.MarketDescriptions;
import com.oddin.oddsfeedsdk.internal.catalog.VoidReasons;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The market description manager and the descriptions it gives, over the real catalogs and REST
 * client, against the fake API. One manager throws and one catches, over the same catalogs.
 */
class DescriptionManagerTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale DE = Locale.GERMAN;
    private static final String DYNAMIC = "od:dynamic_outcomes:770";
    private static final String LIST_EN = "/v1/descriptions/en/markets";
    private static final String LIST_DE = "/v1/descriptions/de/markets";
    private static final String VARIANT_EN = "/v1/descriptions/en/markets/768/variants/" + DYNAMIC;
    private static final String VOID_REASONS = "/v1/descriptions/void_reasons";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private FakeRestServer api;
    private ApiClient client;
    private MarketDescriptions markets;
    private VoidReasons voidReasons;
    private DescriptionManager throwing;
    private DescriptionManager catching;

    @BeforeEach
    void start() {
        api = FakeRestServer.start();
        var configuration = OddsFeed.getOddsFeedConfigurationBuilder()
                .selectEnvironment("mq.invalid", api.apiHost())
                .setAccessToken("token")
                .setHttpClientTimeout(TIMEOUT)
                .build();
        client = new ApiClient(configuration, ApiEvents.NONE);
        markets = new MarketDescriptions(client, TIMEOUT, threads);
        voidReasons = new VoidReasons(client, TIMEOUT, threads);
        throwing = new DescriptionManager(markets, voidReasons, EN, ExceptionHandlingStrategy.THROW, threads);
        catching = new DescriptionManager(markets, voidReasons, EN, ExceptionHandlingStrategy.CATCH, threads);
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        client.close();
        api.close();
    }

    @Test
    void aListedMarketReadsAsTheApiDescribesIt() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        MarketDescription winner = requireNonNull(throwing.getMarketDescription(1, null, EN));
        assertThat(winner.getId()).isEqualTo(1);
        assertThat(winner.getVariant()).isNull();
        assertThat(winner.getName(EN)).isEqualTo("Winner");
        assertThat(winner.getOutcomes())
                .extracting(OutcomeDescription::getId, o -> o.getName(EN), o -> o.getDescription(EN))
                .containsExactly(tuple("1", "Team Alpha", "the first"), tuple("2", "Team Beta", null));
        assertThat(winner.getSpecifiers())
                .extracting(Specifier::getName, Specifier::getType)
                .containsExactly(tuple("variant", "variable_text"));
        assertThat(winner.getGroups()).containsExactly("all", "regular_play");
        assertThat(winner.getIncludesOutcomesOfType()).isNull();
        assertThat(winner.getOutcomeType()).isNull();
        assertDeprecatedRefIdsAreNull(winner);
        assertThat(api.requests("GET", LIST_EN))
                .as("one list, however many getters")
                .hasSize(1);
    }

    @Test
    void aMarketWithoutSpecifiersOrGroupsReadsAsIn0x() {
        api.respond(LIST_EN, 200, list("<market id=\"2\" name=\"Total\"><outcomes/></market>"));
        MarketDescription total = requireNonNull(throwing.getMarketDescription(2, null, EN));
        assertThat(total.getSpecifiers()).isNull();
        assertThat(total.getGroups()).isEmpty();
        assertThat(total.getOutcomes()).isEmpty();
    }

    @Test
    void aDynamicVariantReadsItsOwnDescription() {
        api.respond(VARIANT_EN, 200, list(player(DYNAMIC, "Player Two")));
        MarketDescription market = requireNonNull(throwing.getMarketDescription(768, DYNAMIC, EN));
        assertThat(market.getVariant()).isEqualTo(DYNAMIC);
        assertThat(market.getIncludesOutcomesOfType()).isEqualTo("od:player");
        assertThat(market.getOutcomeType()).isEqualTo(OutcomeType.PLAYER);
        assertThat(market.getOutcomes()).extracting(o -> o.getName(EN)).containsExactly("Player Two");
        assertThat(api.requests("GET", LIST_EN)).isEmpty();
    }

    @Test
    void theListingIsEveryMarketOfTheLocaleInANewListEachTime() {
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions.xml"));
        List<MarketDescription> all = requireNonNull(throwing.getMarketDescriptions());
        assertThat(all)
                .extracting(MarketDescription::getId, MarketDescription::getVariant, d -> d.getName(EN))
                .containsExactly(tuple(1, null, "Winner"), tuple(768, DYNAMIC, "Player to Score"));
        all.clear();
        assertThat(throwing.getMarketDescriptions(EN)).hasSize(2);
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);
    }

    @Test
    void aListedDynamicRowReadsTheListAndAsksNoVariantEndpoint() {
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions.xml"));
        api.respond(
                LIST_DE,
                200,
                list("<market id=\"768\" name=\"Torschütze\" variant=\"" + DYNAMIC + "\">"
                        + "<outcomes><outcome id=\"od:player:9001\" name=\"Spieler Eins\"/></outcomes></market>"));
        api.respond(VARIANT_EN, 404, Fixtures.read("rest/error/not_found.xml"));
        api.respond(
                "/v1/descriptions/de/markets/768/variants/" + DYNAMIC, 404, Fixtures.read("rest/error/not_found.xml"));
        for (DescriptionManager manager : List.of(throwing, catching)) {
            MarketDescription player =
                    requireNonNull(manager.getMarketDescriptions(EN)).get(1);
            assertThat(player.getVariant()).isEqualTo(DYNAMIC);
            assertThat(player.getName(EN)).isEqualTo("Player to Score");
            assertThat(player.getName(DE)).isEqualTo("Torschütze");
            assertThat(player.getOutcomes())
                    .extracting(o -> o.getName(EN), o -> o.getName(DE))
                    .containsExactly(tuple("Player One", "Spieler Eins"));
            assertThat(player.getSpecifiers()).extracting(Specifier::getName).containsExactly("variant");
            assertThat(player.getOutcomeType()).isEqualTo(OutcomeType.PLAYER);
        }
        assertThat(api.requests().stream().filter(request -> request.path().contains("/variants/")))
                .as("no variant's endpoint")
                .isEmpty();
        assertThat(throwing.getMarketDescription(768, DYNAMIC, EN))
                .as("got by id, it is still the variant's own")
                .isNull();
    }

    @Test
    void aVariantFetchedOnItsOwnOverridesTheListRowItIsListedWith() {
        api.respond(LIST_EN, 200, Fixtures.read("rest/markets/market_descriptions.xml"));
        api.respond(VARIANT_EN, 200, list(player(DYNAMIC, "Player Two")));
        MarketDescription listedBefore =
                requireNonNull(throwing.getMarketDescriptions(EN)).get(1);
        assertThat(listedBefore.getOutcomes()).extracting(o -> o.getName(EN)).containsExactly("Player One");

        requireNonNull(throwing.getMarketDescription(768, DYNAMIC, EN));
        MarketDescription listedAfter =
                requireNonNull(throwing.getMarketDescriptions(EN)).get(1);
        assertThat(listedAfter.getOutcomes()).extracting(o -> o.getName(EN)).containsExactly("Player Two");
        assertThat(listedBefore.getOutcomes())
                .extracting(o -> o.getName(EN))
                .as("read now, so the variant held now")
                .containsExactly("Player Two");
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(1);
    }

    @Test
    void aDescriptionInSeveralLocalesThatAllHaveItFollowsTheFirst() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(
                LIST_DE,
                200,
                list("<market id=\"1\" name=\"Sieger\" groups=\"alle\"><outcomes>"
                        + "<outcome id=\"2\" name=\"Mannschaft Beta\"/><outcome id=\"1\" name=\"Team Alpha\"/>"
                        + "</outcomes></market>"));
        for (String read : List.of("cold", "warm")) {
            MarketDescription winner = requireNonNull(throwing.getMarketDescription(1, null, List.of(EN, DE)));
            assertThat(winner.getGroups()).as(read).containsExactly("all", "regular_play");
            assertThat(winner.getOutcomes())
                    .as(read)
                    .extracting(OutcomeDescription::getId)
                    .containsExactly("1", "2");
            assertThat(winner.getSpecifiers()).as(read).hasSize(1);
        }
        assertThat(api.requests("GET", LIST_DE)).hasSize(1);
    }

    @Test
    void aMarketTheCatalogDoesNotHaveIsNullUnderEitherStrategy() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(VARIANT_EN, 404, Fixtures.read("rest/error/not_found.xml"));
        assertThat(throwing.getMarketDescription(99, null, EN)).isNull();
        assertThat(catching.getMarketDescription(99, null, EN)).isNull();
        assertThat(throwing.getMarketDescription(768, DYNAMIC, EN))
                .as("a variant the API does not know")
                .isNull();
        assertThat(catching.getMarketDescription(768, DYNAMIC, EN)).isNull();
    }

    @Test
    void aFailedFetchFailsTheGetterUnderThrowAndIsNullUnderCatch() {
        api.respond(LIST_EN, 400, Fixtures.read("rest/error/not_found.xml"));
        api.respond(VOID_REASONS, 400, Fixtures.read("rest/error/not_found.xml"));
        assertThatThrownBy(() -> throwing.getMarketDescription(1, null, EN)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> throwing.getMarketDescriptions(EN)).isInstanceOf(ApiException.class);
        assertThatThrownBy(throwing::getMarketDescriptions).isInstanceOf(ApiException.class);
        assertThatThrownBy(throwing::getMarketVoidReasons).isInstanceOf(ApiException.class);
        assertThatThrownBy(throwing::reloadMarketVoidReasons).isInstanceOf(ApiException.class);
        assertThat(catching.getMarketDescription(1, null, EN)).isNull();
        assertThat(catching.getMarketDescriptions(EN)).isNull();
        assertThat(catching.getMarketDescriptions()).isNull();
        assertThat(catching.getMarketVoidReasons()).isNull();
        assertThat(catching.reloadMarketVoidReasons()).isNull();
    }

    @Test
    void aDescriptionGoneFromTheCatalogIsNotFoundUnderThrowAndEmptyUnderCatch() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        MarketDescription thrown = requireNonNull(throwing.getMarketDescription(1, null, EN));
        MarketDescription caught = requireNonNull(catching.getMarketDescription(1, null, EN));

        api.respond(LIST_EN, 200, list("<market id=\"2\" name=\"Total\"><outcomes/></market>"));
        throwing.clearMarketDescription(1, null);
        assertThatThrownBy(() -> thrown.getName(EN))
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessageContaining("market description 1 not found in [en]");
        assertThatThrownBy(thrown::getOutcomes).isInstanceOf(ItemNotFoundException.class);
        assertThatThrownBy(thrown::getSpecifiers).isInstanceOf(ItemNotFoundException.class);
        assertThatThrownBy(thrown::getGroups).isInstanceOf(ItemNotFoundException.class);
        assertThat(caught.getName(EN)).isNull();
        assertThat(caught.getOutcomes()).isEmpty();
        assertThat(caught.getSpecifiers()).isNull();
        assertThat(caught.getGroups()).isEmpty();
        assertThat(thrown.getId()).as("as it was got").isEqualTo(1);
        assertThat(refId(thrown)).as("fetching nothing").isNull();
    }

    @Test
    void aNameIsLookedUpInTheLocaleItIsAskedIn() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(LIST_DE, 200, list(winner("Sieger", "Team Alpha", "Mannschaft Beta")));
        MarketDescription winner = requireNonNull(throwing.getMarketDescription(1, null, EN));
        List<OutcomeDescription> outcomes = winner.getOutcomes();
        assertThat(api.requests("GET", LIST_DE))
                .as("not before a name is asked in it")
                .isEmpty();

        assertThat(winner.getName(DE)).isEqualTo("Sieger");
        assertThat(outcomes).extracting(o -> o.getName(DE)).containsExactly("Team Alpha", "Mannschaft Beta");
        assertThat(api.requests("GET", LIST_DE)).hasSize(1);
    }

    @Test
    void anOutcomeNotDescribedInALocaleHasNoNameThere() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(
                LIST_DE,
                200,
                list(
                        "<market id=\"1\" name=\"Sieger\"><outcomes><outcome id=\"2\" name=\"Beta\"/></outcomes></market>"));
        List<OutcomeDescription> outcomes =
                requireNonNull(throwing.getMarketDescription(1, null, EN)).getOutcomes();
        assertThat(outcomes).extracting(o -> o.getName(DE)).containsExactly(null, "Beta");
        assertThat(outcomes).extracting(o -> o.getDescription(DE)).containsExactly(null, null);
    }

    @Test
    void aMarketMissingFromTheListOfALocaleIsNotFoundThereUnderThrow() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(LIST_DE, 200, list("<market id=\"2\" name=\"Summe\"><outcomes/></market>"));
        MarketDescription thrown = requireNonNull(throwing.getMarketDescription(1, null, EN));
        MarketDescription caught = requireNonNull(catching.getMarketDescription(1, null, EN));
        assertThatThrownBy(() -> thrown.getName(DE)).isInstanceOf(ItemNotFoundException.class);
        assertThat(caught.getName(DE)).isNull();
        assertThat(thrown.getOutcomes())
                .extracting(o -> o.getName(DE))
                .as("an outcome's name is null, as in 0.0.x")
                .containsExactly(null, null);
    }

    @Test
    void anOutcomeNameThatCannotBeFetchedFailsUnderThrowAndIsNullUnderCatch() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(LIST_DE, 400, Fixtures.read("rest/error/not_found.xml"));
        OutcomeDescription thrown = requireNonNull(throwing.getMarketDescription(1, null, EN))
                .getOutcomes()
                .getFirst();
        OutcomeDescription caught = requireNonNull(catching.getMarketDescription(1, null, EN))
                .getOutcomes()
                .getFirst();
        assertThatThrownBy(() -> thrown.getName(DE)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> thrown.getDescription(DE)).isInstanceOf(ApiException.class);
        assertThat(caught.getName(DE)).isNull();
        assertThat(caught.getDescription(DE)).isNull();
        assertThat(caught.getName(EN)).isEqualTo("Team Alpha");
    }

    @Test
    void aDescriptionInSeveralLocalesLoadsThemInParallel() {
        api.respond(
                LIST_EN,
                Reply.of(200, list(winner("Winner", "Team Alpha", "Team Beta"))).after(Duration.ofMillis(300)));
        api.respond(
                LIST_DE,
                Reply.of(200, list(winner("Sieger", "Team Alpha", "Mannschaft Beta")))
                        .after(Duration.ofMillis(300)));
        MarketDescription winner = requireNonNull(throwing.getMarketDescription(1, null, List.of(EN, DE)));
        assertThat(api.mostInFlight()).as("both lists at once").isEqualTo(2);
        assertThat(winner.getName(EN)).isEqualTo("Winner");
        assertThat(winner.getName(DE)).isEqualTo("Sieger");
        assertThat(api.requests("GET", LIST_EN)).hasSize(1);
        assertThat(api.requests("GET", LIST_DE))
                .as("loaded with the description")
                .hasSize(1);
    }

    @Test
    void aDescriptionInSeveralLocalesIsTheFirstLocaleThatHasIt() {
        api.respond(LIST_EN, 200, list("<market id=\"2\" name=\"Total\"><outcomes/></market>"));
        api.respond(LIST_DE, 200, list(winner("Sieger", "Team Alpha", "Mannschaft Beta")));
        MarketDescription winner = requireNonNull(throwing.getMarketDescription(1, null, List.of(EN, DE)));
        assertThat(winner.getOutcomes())
                .extracting(o -> o.getName(DE))
                .containsExactly("Team Alpha", "Mannschaft Beta");
        assertThat(winner.getGroups()).containsExactly("all", "regular_play");
        assertThat(throwing.getMarketDescription(3, null, List.of(EN, DE))).isNull();
        assertThat(requireNonNull(throwing.getMarketDescription(2, null, List.of()))
                        .getName(EN))
                .as("no locale is the default one")
                .isEqualTo("Total");
    }

    @Test
    void aLocaleThatFailsFailsTheDescriptionInSeveralUnderThrowAndMakesItNullUnderCatch() {
        api.respond(LIST_EN, 200, list(winner("Winner", "Team Alpha", "Team Beta")));
        api.respond(LIST_DE, 400, Fixtures.read("rest/error/not_found.xml"));
        assertThatThrownBy(() -> throwing.getMarketDescription(1, null, List.of(EN, DE)))
                .isInstanceOf(ApiException.class);
        assertThat(catching.getMarketDescription(1, null, List.of(EN, DE)))
                .as("never a description from part of the locales")
                .isNull();
        assertThat(catching.getMarketDescription(1, null, List.of(EN))).isNotNull();
    }

    @Test
    void clearingAMarketFetchesItAgain() {
        api.respond(VARIANT_EN, 200, list(player(DYNAMIC, "Player Two")));
        throwing.getMarketDescription(768, DYNAMIC, EN);
        throwing.getMarketDescription(1, null, EN);
        throwing.clearMarketDescription(768, DYNAMIC);
        throwing.getMarketDescription(768, DYNAMIC, EN);
        throwing.getMarketDescription(1, null, EN);
        assertThat(api.requests("GET", VARIANT_EN)).hasSize(2);
        assertThat(api.requests("GET", LIST_EN)).as("the variant alone").hasSize(1);

        throwing.clearMarketDescription(1, null);
        throwing.getMarketDescription(1, null, EN);
        assertThat(api.requests("GET", LIST_EN)).hasSize(2);
    }

    @Test
    void theVoidReasonsReadAsTheApiDescribesThemInANewListEachTime() {
        List<MarketVoidReason> reasons = requireNonNull(throwing.getMarketVoidReasons());
        assertThat(reasons)
                .extracting(
                        MarketVoidReason::getId,
                        MarketVoidReason::getName,
                        MarketVoidReason::getDescription,
                        MarketVoidReason::getTemplate,
                        MarketVoidReason::getParams)
                .containsExactly(
                        tuple(1, "NOT_PLAYED", "Match not played", null, List.of()),
                        tuple(4, "LATE_START", "Match started late", "Late by {minutes} minutes", List.of("minutes")));
        reasons.clear();
        assertThat(throwing.getMarketVoidReasons()).hasSize(2);
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(1);
    }

    @Test
    void clearingTheVoidReasonsFetchesThemAgainAndAReloadFetchesThemNow() {
        throwing.getMarketVoidReasons();
        throwing.clearMarketVoidReasons();
        throwing.getMarketVoidReasons();
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(2);

        api.respond(
                VOID_REASONS,
                200,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><void_reasons response_code=\"OK\">"
                        + "<void_reason id=\"7\" name=\"OTHER\" description=\"Other\"/></void_reasons>");
        assertThat(throwing.reloadMarketVoidReasons())
                .extracting(MarketVoidReason::getId)
                .containsExactly(7);
        assertThat(throwing.getMarketVoidReasons())
                .extracting(MarketVoidReason::getId)
                .containsExactly(7);
        assertThat(api.requests("GET", VOID_REASONS)).hasSize(3);
    }

    @SuppressWarnings("deprecation") // the deprecated getters on purpose
    private static void assertDeprecatedRefIdsAreNull(MarketDescription market) {
        assertThat(market.getRefId()).isNull();
        for (OutcomeDescription outcome : market.getOutcomes()) {
            assertThat(outcome.getRefId()).isNull();
        }
    }

    @SuppressWarnings("deprecation") // the deprecated getter on purpose
    private static @Nullable Integer refId(MarketDescription market) {
        return market.getRefId();
    }

    private static String winner(String name, String first, String second) {
        return "<market id=\"1\" name=\"" + name + "\" groups=\"all|regular_play\"><outcomes>"
                + "<outcome id=\"1\" name=\"" + first + "\" description=\"the first\"/>"
                + "<outcome id=\"2\" name=\"" + second + "\"/></outcomes>"
                + "<specifiers><specifier name=\"variant\" type=\"variable_text\"/></specifiers></market>";
    }

    private static String player(String variant, String player) {
        return "<market id=\"768\" name=\"Player to Score\" variant=\"" + variant + "\""
                + " includes_outcomes_of_type=\"od:player\" outcome_type=\"player\">"
                + "<outcomes><outcome id=\"od:player:2\" name=\"" + player + "\"/></outcomes></market>";
    }

    private static String list(String... markets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><market_descriptions response_code=\"OK\">"
                + String.join("", markets) + "</market_descriptions>";
    }
}
