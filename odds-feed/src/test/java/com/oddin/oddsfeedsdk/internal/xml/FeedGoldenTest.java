package com.oddin.oddsfeedsdk.internal.xml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
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
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
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
        Path vendored = vendored();
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
                        .as("%s, lenient", fixture.getFileName())
                        .isInstanceOf(entry.getValue());
                decoded.add(entry.getKey() + "/" + fixture.getFileName());
            }
        }
        // a fixture directory that yields nothing would pass the loop above
        assertThat(decoded).as("fixtures decoded").hasSizeGreaterThan(CLASSES.size());
    }

    /**
     * What each fixture decodes to, written back as XML, is the fixture: every element and
     * attribute in the same place with the same value. So nothing is dropped, and nothing lands in
     * a field that holds it differently - which the class of the result alone does not show.
     */
    @Test
    void everyFixtureDecodesToWhatItSays() throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory.class)
                .createMarshaller();
        var differences = new ArrayList<String>();
        for (String directory : CLASSES.keySet()) {
            for (Path fixture : xmlFiles(fixtures.resolve(directory))) {
                var written = new StringWriter();
                marshaller.marshal(strict.decode(Files.readAllBytes(fixture)), written);
                compare(
                        parse(new InputSource(fixture.toUri().toString())),
                        parse(new InputSource(new StringReader(written.toString()))),
                        directory + "/" + fixture.getFileName(),
                        differences);
            }
        }
        assertThat(differences).as("what the fixtures decode to, written back").isEmpty();
    }

    private static Element parse(InputSource source) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(source).getDocumentElement();
    }

    /** Elements by name and order, attributes by name and value; numbers compare as numbers. */
    private static void compare(Element expected, Element actual, String where, List<String> differences) {
        String path = where + " <" + expected.getLocalName() + ">";
        if (!expected.getLocalName().equals(actual.getLocalName())) {
            differences.add(path + ": written back as <" + actual.getLocalName() + ">");
            return;
        }
        var want = attributes(expected);
        var got = attributes(actual);
        for (var entry : want.entrySet()) {
            String value = got.get(entry.getKey());
            if (value == null) {
                differences.add(path + " @" + entry.getKey() + ": lost");
            } else if (!sameValue(entry.getValue(), value)) {
                differences.add(path + " @" + entry.getKey() + ": " + entry.getValue() + " came back as " + value);
            }
        }
        got.keySet().stream()
                .filter(name -> !want.containsKey(name))
                .forEach(name -> differences.add(path + " @" + name + ": appeared"));
        List<Element> wantChildren = children(expected);
        List<Element> gotChildren = children(actual);
        if (wantChildren.size() != gotChildren.size()) {
            differences.add(path + ": " + wantChildren.size() + " child elements came back as " + gotChildren.size());
            return;
        }
        for (int i = 0; i < wantChildren.size(); i++) {
            compare(wantChildren.get(i), gotChildren.get(i), path, differences);
        }
    }

    private static Map<String, String> attributes(Element element) {
        var attributes = new TreeMap<String, String>();
        var all = element.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            var attribute = all.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                attributes.put(attribute.getLocalName(), attribute.getNodeValue());
            }
        }
        return attributes;
    }

    private static List<Element> children(Element element) {
        var children = new ArrayList<Element>();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    /** "0.50" and "0.5" are the same number; "true" and "1" are not the same text. */
    private static boolean sameValue(String expected, String actual) {
        if (expected.equals(actual)) {
            return true;
        }
        try {
            return new BigDecimal(expected).compareTo(new BigDecimal(actual)) == 0;
        } catch (NumberFormatException notNumbers) {
            return false;
        }
    }

    @Test
    void everyFixtureDirectoryIsCovered() throws IOException {
        // A message family added upstream must be placed here on purpose, not skipped quietly.
        try (Stream<Path> directories = Files.list(fixtures)) {
            assertThat(directories
                            .filter(Files::isDirectory)
                            .map(d -> d.getFileName().toString()))
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
            includes.append("  <xs:include schemaLocation=\"")
                    .append(xsd.getFileName())
                    .append("\"/>\n");
        }
        String all = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
                %s</xs:schema>
                """.formatted(includes);
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        Source source = new StreamSource(
                new StringReader(all), directory.resolve("all.xsd").toUri().toString());
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
        URL source = requireNonNull(
                FeedGoldenTest.class.getResource("/oddsfeedschema/SOURCE"),
                "the vendored schema is not on the test classpath");
        return requireNonNull(Path.of(source.toURI()).getParent());
    }
}
