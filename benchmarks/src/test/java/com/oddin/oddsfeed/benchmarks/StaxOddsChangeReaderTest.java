package com.oddin.oddsfeed.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

/**
 * The StAX reader decodes what JAXB decodes: both results, written back by JAXB, are the same XML.
 * Without that, comparing their speed would compare different work.
 */
class StaxOddsChangeReaderTest {

    private final FeedDecoder jaxb = FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES);
    private final StaxOddsChangeReader stax = new StaxOddsChangeReader();

    @Test
    void theCorpusDecodesTheSameBothWays() throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        for (int markets : new int[] {Corpus.SMALL, Corpus.TYPICAL, Corpus.LARGE}) {
            byte[] body = Corpus.oddsChange(markets);
            assertThat(written(marshaller, stax.read(body)))
                    .as("%d markets", markets)
                    .isEqualTo(written(marshaller, jaxb.decode(body)));
        }
    }

    @Test
    void everyVendoredOddsChangeDecodesTheSameBothWays() throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        Path fixtures = CorpusTest.vendoredSchema()
                .resolve("../../test/fixtures/feed/odds_change")
                .normalize();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(fixtures)) {
            list.filter(f -> f.toString().endsWith(".xml")).sorted().forEach(files::add);
        }
        assertThat(files).isNotEmpty();
        for (Path fixture : files) {
            byte[] body = Files.readAllBytes(fixture);
            assertThat(written(marshaller, stax.read(body)))
                    .as(fixture.getFileName().toString())
                    .isEqualTo(written(marshaller, jaxb.decode(body)));
        }
    }

    private static String written(Marshaller marshaller, Object decoded) throws Exception {
        var out = new StringWriter();
        marshaller.marshal(requireNonNull(decoded), out);
        return out.toString();
    }

    @Test
    void valuesAreReadByXmlSchemasRules() throws Exception {
        // the corpus and the fixtures write true, false and plain numbers; the schema allows more
        byte[] body = """
                <odds_change product=" 2 " event_id="od:match:1" timestamp="1777832981632">\
                <sport_event_status status="1" match_status="52" home_score=" 1 " away_score="0"\
                 scoreboard_available="1">\
                <period_scores><period_score type="map" number="1" match_status_code="51" home_score="1"\
                 away_score="0" home_won_coin_toss="0"/></period_scores>\
                <scoreboard home_batting="1" away_batting="false" current_round=" 7"/>\
                </sport_event_status>\
                <odds><market id="1" status="1"><outcome id="1" odds="INF" active="1"/>\
                <outcome id="2" odds="-INF" probabilities="NaN" active="0"/></market></odds>\
                </odds_change>""".getBytes(UTF_8);
        OFOddsChange decoded = stax.read(body);
        assertThat(decoded.getSportEventStatus().getScoreboardAvailable()).isTrue();
        assertThat(decoded.getSportEventStatus().getScoreboard().getHomeBatting())
                .isTrue();
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        assertThat(written(marshaller, decoded)).isEqualTo(written(marshaller, jaxb.decode(body)));
    }

    @Test
    void anotherMessageIsRefusedAsTheDecoderRefusesIt() {
        byte[] betStop = "<bet_stop product=\"2\" event_id=\"od:match:1\" timestamp=\"1\"/>".getBytes(UTF_8);
        assertThatThrownBy(() -> stax.read(betStop))
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("not an odds change: bet_stop");
    }
}
