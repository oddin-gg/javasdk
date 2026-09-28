package com.oddin.oddsfeedsdk.internal.xml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFSnapshotComplete;
import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

/**
 * Every feed fixture vendored from the oddsfeedschema repository, decoded strictly: validated
 * against the vendored schema, with anything the generated classes could not place failing the
 * test. The fixtures are what the producers send, so a mismatch here is drift between the schema,
 * the fixtures and these classes, and it shows up in CI rather than at a client.
 */
class FeedGoldenTest {

    /** What each fixture directory must decode to. */
    private static final Map<String, Class<?>> CLASSES = Map.of(
            "alive", OFAlive.class,
            "bet_cancel", OFBetCancel.class,
            "bet_settlement", OFBetSettlement.class,
            "bet_stop", OFBetStop.class,
            "fixture_change", OFFixtureChange.class,
            "odds_change", OFOddsChange.class,
            "rollback_bet_cancel", OFRollbackBetCancel.class,
            "rollback_bet_settlement", OFRollbackBetSettlement.class,
            "snapshot_complete", OFSnapshotComplete.class);

    private static Path fixtures;
    private static FeedDecoder strict;

    @BeforeAll
    static void loadTheSchema() throws Exception {
        Path vendored = Path.of(FeedGoldenTest.class.getResource("/oddsfeedschema/SOURCE").toURI()).getParent();
        fixtures = vendored.resolve("test/fixtures/feed");
        strict = FeedDecoder.strict(FeedDecoder.DEFAULT_MAX_BYTES, feedSchema(vendored.resolve("schema/feed")));
    }

    @Test
    void everyFixtureDecodesStrictlyToItsClass() throws IOException, DecodeException {
        var decoded = new ArrayList<String>();
        for (var entry : CLASSES.entrySet()) {
            List<Path> files = xmlFiles(fixtures.resolve(entry.getKey()));
            // an emptied directory would skip its message family without a word
            assertThat(files).as("fixtures of %s", entry.getKey()).isNotEmpty();
            for (Path fixture : files) {
                byte[] body = Files.readAllBytes(fixture);
                UnparsedMessage message = strict.decode(body);
                assertThat(message).as(fixture.getFileName().toString()).isInstanceOf(entry.getValue());
                assertThat(FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES).decode(body))
                        .as("%s, lenient", fixture.getFileName()).isInstanceOf(entry.getValue());
                decoded.add(entry.getKey() + "/" + fixture.getFileName());
            }
        }
        // a fixture directory that yields nothing would pass the loop above
        assertThat(decoded).as("fixtures decoded").hasSizeGreaterThan(CLASSES.size());
    }

    @Test
    void everyFixtureDirectoryIsCovered() throws IOException {
        // A message family added upstream must be placed here on purpose, not skipped quietly.
        try (Stream<Path> directories = Files.list(fixtures)) {
            assertThat(directories.filter(Files::isDirectory).map(d -> d.getFileName().toString()))
                    .containsExactlyInAnyOrderElementsOf(CLASSES.keySet());
        }
    }

    @Test
    void anAliveCarriesItsProducerTimeAndSubscription() throws Exception {
        var alive = (OFAlive) strict.decode(fixture("alive/alive.xml"));
        assertThat(alive.getProduct()).isEqualTo(1);
        assertThat(alive.getTimestamp()).isEqualTo(1777832981632L);
        assertThat(alive.getSubscribed()).isEqualTo(1);
    }

    @Test
    void aSnapshotCompleteCarriesItsRequest() throws Exception {
        var complete = (OFSnapshotComplete) strict.decode(fixture("snapshot_complete/snapshot_complete.xml"));
        assertThat(complete.getProduct()).isEqualTo(1);
        assertThat(complete.getRequestId()).isEqualTo(712L);
        assertThat(complete.getTimestamp()).isEqualTo(1777832981632L);
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(fixtures.resolve(name));
    }

    static List<Path> xmlFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.toString().endsWith(".xml")).sorted().toList();
        }
    }

    /**
     * Every feed XSD in one schema. They have no target namespace, and a schema factory given several
     * such documents keeps only the first, so one document includes them all instead.
     */
    static Schema feedSchema(Path directory) throws IOException, SAXException {
        var includes = new StringBuilder();
        for (Path xsd : xsdFiles(directory)) {
            includes.append("  <xs:include schemaLocation=\"").append(xsd.getFileName()).append("\"/>\n");
        }
        String all = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
                %s</xs:schema>
                """.formatted(includes);
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        Source source = new StreamSource(new StringReader(all), directory.resolve("all.xsd").toUri().toString());
        return factory.newSchema(source);
    }

    private static List<Path> xsdFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.toString().endsWith(".xsd")).sorted().toList();
        }
    }

    static byte[] bytes(String xml) {
        return xml.getBytes(UTF_8);
    }

    static Path vendored() throws URISyntaxException {
        return Path.of(FeedGoldenTest.class.getResource("/oddsfeedschema/SOURCE").toURI()).getParent();
    }
}
