package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
    void theReaderIsForOddsChanges() throws Exception {
        assertThat(stax.read(Corpus.oddsChange(1))).isInstanceOf(OFOddsChange.class);
    }
}
