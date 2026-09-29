package com.oddin.oddsfeedsdk.internal.xml;

import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.compareWrittenBack;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.schema;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.vendored;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.xmlFiles;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        strict = FeedDecoder.strict(FeedDecoder.DEFAULT_MAX_BYTES, schema(vendored.resolve("schema/feed")));
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
                compareWrittenBack(
                        fixture,
                        strict.decode(Files.readAllBytes(fixture)),
                        marshaller,
                        directory + "/" + fixture.getFileName(),
                        differences);
            }
        }
        assertThat(differences).as("what the fixtures decode to, written back").isEmpty();
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
}
