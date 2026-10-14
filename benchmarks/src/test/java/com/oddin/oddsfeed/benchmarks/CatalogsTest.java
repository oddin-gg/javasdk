package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitorProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescription;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASport;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportsEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeamCompetitor;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The catalogs are what the API may answer, at the sizes asked for, and the SDK reads them. */
class CatalogsTest {

    private static final int MATCHES = 200;

    private static Schema restSchema;
    // the decoder the SDK reads responses with; strict, so it also validates against the schema
    private static RestDecoder sdk;

    @BeforeAll
    static void loadTheSchema() throws Exception {
        restSchema = restSchema();
        sdk = RestDecoder.strict(RestDecoder.DEFAULT_MAX_BYTES, restSchema);
    }

    @Test
    void catalogsHaveTheProductionShape() throws Exception {
        var markets = decode(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS), RAMarketDescriptions.class);
        assertThat(markets.getMarket()).hasSize(Catalogs.MARKET_DESCRIPTIONS);
        assertThat(markets.getMarket().stream().map(RAMarketDescription::getId).collect(Collectors.toSet()))
                .as("distinct market ids")
                .hasSize(Catalogs.MARKET_DESCRIPTIONS);
        assertThat(markets.getMarket().stream()
                        .mapToInt(m -> m.getOutcomes().getOutcome().size())
                        .summaryStatistics())
                .satisfies(outcomes -> {
                    assertThat(outcomes.getMin()).isEqualTo(2);
                    assertThat(outcomes.getMax()).isEqualTo(20);
                });
        assertThat(markets.getMarket().stream().filter(m -> m.getVariant() != null))
                .as("markets with a variant")
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.getId()).isGreaterThan(Corpus.MARKET_IDS));
        assertThat(markets.getMarket().stream().filter(m -> m.getSpecifiers() != null))
                .as("markets with specifiers")
                .isNotEmpty();

        var sports = decode(Catalogs.sports(Catalogs.SPORTS), RASportsEndpoint.class);
        assertThat(sports.getSport()).hasSize(Catalogs.SPORTS);
        assertThat(sports.getSport().stream().map(RASport::getId)).doesNotHaveDuplicates();
    }

    @Test
    void everyMarketIdOfTheCorpusHasADescription() throws Exception {
        var described = decode(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS), RAMarketDescriptions.class)
                .getMarket()
                .stream()
                .filter(m -> m.getVariant() == null)
                .map(RAMarketDescription::getId)
                .collect(Collectors.toSet());
        assertThat(described)
                .containsAll(IntStream.rangeClosed(1, Corpus.MARKET_IDS).boxed().toList());

        // and the ids the corpus really draws, not only the range it says it draws from
        FeedDecoder feed = FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES);
        for (int markets : new int[] {Corpus.SMALL, Corpus.TYPICAL, Corpus.LARGE}) {
            var change = (OFOddsChange) feed.decode(Corpus.oddsChange(markets));
            assertThat(change.getOdds().getMarket())
                    .allSatisfy(m -> assertThat(described).contains(m.getId()));
        }
    }

    @Test
    void everyMatchHasItsOwnCompetitorsAndEveryCompetitorAProfile() throws Exception {
        Set<String> competitors = new HashSet<>();
        Set<String> sportIds = decode(Catalogs.sports(Catalogs.SPORTS), RASportsEndpoint.class).getSport().stream()
                .map(RASport::getId)
                .collect(Collectors.toSet());
        for (long match = 198_000; match < 198_000 + MATCHES; match++) {
            var summary = decode(Catalogs.matchSummary(match), RAMatchSummaryEndpoint.class);
            var event = summary.getSportEvent();
            assertThat(event.getId()).isEqualTo("od:match:" + match);
            assertThat(event.getTournament().getId()).startsWith("od:tournament:");
            assertThat(sportIds).contains(event.getTournament().getSport().getId());
            List<RATeamCompetitor> teams = event.getCompetitors().getCompetitor();
            assertThat(teams).hasSize(Catalogs.COMPETITORS_PER_MATCH);
            for (RATeamCompetitor team : teams) {
                assertThat(competitors.add(team.getId()))
                        .as("%s of match %d is new", team.getId(), match)
                        .isTrue();
                var profile = decode(
                        Catalogs.competitorProfile(Long.parseLong(team.getId().substring("od:competitor:".length()))),
                        RACompetitorProfileEndpoint.class);
                assertThat(profile.getCompetitor().getId()).isEqualTo(team.getId());
                assertThat(sportIds).contains(profile.getCompetitor().getSport().getId());
                assertThat(profile.getPlayers()).isNotEmpty();
            }
        }
        assertThat(competitors).hasSize(MATCHES * Catalogs.COMPETITORS_PER_MATCH);
    }

    @Test
    void aVariantsDescriptionIsTheMarketWithThatVariantAndItsPlayers() throws Exception {
        var variant = "od:dynamic_outcomes:401:5000000:3";
        var described = decode(Catalogs.variantDescription(401, variant), RAMarketDescriptions.class);

        assertThat(described.getMarket()).hasSize(1);
        var market = described.getMarket().get(0);
        assertThat(market.getId()).isEqualTo(401);
        assertThat(market.getVariant()).isEqualTo(variant);
        assertThat(market.getOutcomes().getOutcome())
                .hasSizeBetween(6, 20)
                .allSatisfy(outcome -> assertThat(outcome.getId()).startsWith("od:player:"));
        assertThat(Catalogs.variantDescription(401, variant)).isEqualTo(Catalogs.variantDescription(401, variant));
        var other = decode(
                Catalogs.variantDescription(401, "od:dynamic_outcomes:401:5000001:3"), RAMarketDescriptions.class);
        assertThat(playersOf(other))
                .as("another match's variant has other players")
                .isNotEmpty()
                .doesNotContainAnyElementsOf(playersOf(described));
        assertThat(playersOf(decode(Catalogs.variantDescription(401, variant), RAMarketDescriptions.class)))
                .as("the same variant has the same players")
                .isEqualTo(playersOf(described));
        assertThatThrownBy(() -> Catalogs.variantDescription(401, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizesAreParameters() throws Exception {
        assertThat(decode(Catalogs.marketDescriptions(7), RAMarketDescriptions.class)
                        .getMarket())
                .hasSize(7);
        assertThat(decode(Catalogs.sports(3), RASportsEndpoint.class).getSport())
                .hasSize(3);
        var summary = decode(Catalogs.matchSummary(5, 4, 3), RAMatchSummaryEndpoint.class);
        assertThat(summary.getSportEvent().getCompetitors().getCompetitor()).hasSize(4);
        assertThat(summary.getSportEvent().getTournament().getSport().getId())
                .isIn("od:sport:1", "od:sport:2", "od:sport:3");
        assertThat(IntStream.range(0, 4)
                        .mapToLong(slot -> Catalogs.competitorId(5, slot, 4))
                        .distinct()
                        .count())
                .isEqualTo(4);
        assertThatThrownBy(() -> Catalogs.marketDescriptions(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameInputIsTheSameBytes() {
        assertThat(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS))
                .isEqualTo(Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS));
        assertThat(Catalogs.sports(Catalogs.SPORTS)).isEqualTo(Catalogs.sports(Catalogs.SPORTS));
        assertThat(Catalogs.matchSummary(198_314)).isEqualTo(Catalogs.matchSummary(198_314));
        assertThat(Catalogs.competitorProfile(100_123)).isEqualTo(Catalogs.competitorProfile(100_123));
        assertThat(Catalogs.matchSummary(198_314)).isNotEqualTo(Catalogs.matchSummary(198_315));
    }

    @Test
    void everyDocumentValidatesAgainstTheVendoredSchema() throws Exception {
        for (byte[] document : List.of(
                Catalogs.marketDescriptions(Catalogs.MARKET_DESCRIPTIONS),
                Catalogs.sports(Catalogs.SPORTS),
                Catalogs.matchSummary(198_314),
                Catalogs.variantDescription(401, "od:dynamic_outcomes:401:5000000:3"),
                Catalogs.competitorProfile(100_123))) {
            restSchema.newValidator().validate(new StreamSource(new java.io.ByteArrayInputStream(document)));
        }
        // the validator rejects what it should: an element the schema does not have
        byte[] broken = new String(Catalogs.sports(2), java.nio.charset.StandardCharsets.UTF_8)
                .replace("<sport ", "<sprot ")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() ->
                        restSchema.newValidator().validate(new StreamSource(new java.io.ByteArrayInputStream(broken))))
                .isInstanceOf(org.xml.sax.SAXException.class);
    }

    /** The ids of the outcomes of the one market a variant's description holds. */
    private static List<String> playersOf(RAMarketDescriptions described) {
        return described.getMarket().get(0).getOutcomes().getOutcome().stream()
                .map(outcome -> outcome.getId())
                .toList();
    }

    private static <T> T decode(byte[] body, Class<T> type) throws DecodeException {
        return sdk.decode(body, type);
    }

    /** Every REST XSD in one schema; they have no namespace, so one document includes them all. */
    private static Schema restSchema() throws Exception {
        var xsd = requireNonNull(CatalogsTest.class.getResource("/oddsfeedschema/schema/rest/markets.xsd"));
        Path directory = requireNonNull(Path.of(xsd.toURI()).getParent());
        var includes = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".xsd")).sorted().toList()) {
                includes.append("<xs:include schemaLocation=\"")
                        .append(file.getFileName())
                        .append("\"/>");
            }
        }
        String all = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\">"
                + includes + "</xs:schema>";
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        return factory.newSchema(new StreamSource(
                new StringReader(all), directory.resolve("all.xsd").toUri().toString()));
    }
}
