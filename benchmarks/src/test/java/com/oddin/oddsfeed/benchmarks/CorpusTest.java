package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;

/** The corpus is what the feed may send, and the sizes are what they say. */
class CorpusTest {

    @Test
    void everySizeIsAValidOddsChangeWithItsMarkets() throws Exception {
        FeedDecoder strict = FeedDecoder.strict(FeedDecoder.DEFAULT_MAX_BYTES, feedSchema());
        for (int markets : new int[] {Corpus.SMALL, Corpus.TYPICAL, Corpus.LARGE}) {
            var change = (OFOddsChange) strict.decode(Corpus.oddsChange(markets));
            assertThat(change.getOdds().getMarket())
                    .as("markets of %d", markets)
                    .hasSize(markets);
            assertThat(change.getSportEventStatus().getScoreboard()).isNotNull();
        }
    }

    @Test
    void variantMarketsFollowTheCorpusWithAVariantOfTheirOwn() throws Exception {
        FeedDecoder strict = FeedDecoder.strict(FeedDecoder.DEFAULT_MAX_BYTES, feedSchema());
        var change = (OFOddsChange) strict.decode(Corpus.oddsChange(Corpus.TYPICAL, 20, 5_000_000));
        var markets = change.getOdds().getMarket();
        assertThat(markets).hasSize(Corpus.TYPICAL + 20);
        var variants = markets.subList(Corpus.TYPICAL, markets.size()).stream()
                .map(m -> m.getSpecifiers())
                .toList();
        assertThat(variants)
                .doesNotHaveDuplicates()
                .allSatisfy(s -> assertThat(s).startsWith("variant=od:dynamic_outcomes:"));

        var other = (OFOddsChange) strict.decode(Corpus.oddsChange(Corpus.TYPICAL, 20, 5_000_001));
        assertThat(other.getOdds().getMarket().subList(Corpus.TYPICAL, Corpus.TYPICAL + 20).stream()
                        .map(m -> m.getSpecifiers()))
                .as("no variant of another match's")
                .doesNotContainAnyElementsOf(variants);
        assertThat(Corpus.oddsChange(Corpus.TYPICAL, 20, 5_000_000))
                .isEqualTo(Corpus.oddsChange(Corpus.TYPICAL, 20, 5_000_000));
        assertThat(new String(Corpus.oddsChange(Corpus.TYPICAL), java.nio.charset.StandardCharsets.UTF_8))
                .as("the plain corpus does have such a market, which the variant mode leaves out")
                .contains("variant=way:two");
        assertThat(markets.subList(0, Corpus.TYPICAL))
                .as("no market of the corpus names a variant but the dynamic ones")
                .noneMatch(m -> m.getSpecifiers() != null && m.getSpecifiers().contains("variant="));
    }

    @Test
    void theSameSizeIsTheSameBytes() {
        assertThat(Corpus.oddsChange(Corpus.TYPICAL)).isEqualTo(Corpus.oddsChange(Corpus.TYPICAL));
    }

    @Test
    void aTypicalOddsChangeIsTensOfKilobytes() {
        // the size a live match's odds change runs to; far under the decoder's 1 MiB limit
        assertThat(Corpus.oddsChange(Corpus.TYPICAL).length).isBetween(20_000, 100_000);
        assertThat(Corpus.oddsChange(Corpus.LARGE).length).isLessThan(FeedDecoder.DEFAULT_MAX_BYTES / 4);
    }

    static Path vendoredSchema() throws Exception {
        var xsd = requireNonNull(CorpusTest.class.getResource("/oddsfeedschema/schema/feed/odds_change.xsd"));
        return requireNonNull(Path.of(xsd.toURI()).getParent());
    }

    /** Every feed XSD in one schema; they have no namespace, so one document includes them all. */
    static Schema feedSchema() throws Exception {
        Path directory = vendoredSchema();
        var includes = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path xsd :
                    files.filter(f -> f.toString().endsWith(".xsd")).sorted().toList()) {
                includes.append("<xs:include schemaLocation=\"")
                        .append(xsd.getFileName())
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
