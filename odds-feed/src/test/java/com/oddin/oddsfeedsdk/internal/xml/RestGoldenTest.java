package com.oddin.oddsfeedsdk.internal.xml;

import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.compareWrittenBack;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.schema;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.vendored;
import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.xmlFiles;
import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.schema.rest.v1.ObjectFactory;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitorProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RADatetournaments;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAError;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixtureChangesEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAFixturesEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketVoidReasons;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchStatusDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayerProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplaySetContent;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplayStatusEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAScheduleEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportTournaments;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportsEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentInfo;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATournamentSchedule;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every REST fixture vendored from the oddsfeedschema repository, decoded strictly: validated against
 * the vendored schema, with anything the generated classes could not place failing the test. The
 * fixtures are what the API returns, so a mismatch here is drift between the schema, the fixtures and
 * these classes.
 */
class RestGoldenTest {

    /** What each fixture directory must decode to. */
    private static final Map<String, Class<?>> CLASSES = Map.ofEntries(
            Map.entry("competitor", RACompetitorProfileEndpoint.class),
            Map.entry("error", RAError.class),
            Map.entry("fixture_changes", RAFixtureChangesEndpoint.class),
            Map.entry("fixtures_fixture", RAFixturesEndpoint.class),
            Map.entry("markets", RAMarketDescriptions.class),
            Map.entry("match_status", RAMatchStatusDescriptions.class),
            Map.entry("match_summary", RAMatchSummaryEndpoint.class),
            Map.entry("player", RAPlayerProfileEndpoint.class),
            Map.entry("producers", RAProducers.class),
            Map.entry("replay_content", RAReplaySetContent.class),
            Map.entry("replay_status", RAReplayStatusEndpoint.class),
            Map.entry("schedule", RAScheduleEndpoint.class),
            Map.entry("sport_tournaments", RASportTournaments.class),
            Map.entry("sports", RASportsEndpoint.class),
            Map.entry("tournament_info", RATournamentInfo.class),
            Map.entry("tournament_schedule", RATournamentSchedule.class),
            // the tournaments endpoint's root element is of the datetournaments type
            Map.entry("tournaments", RADatetournaments.class),
            Map.entry("void_reasons", RAMarketVoidReasons.class),
            Map.entry("whoami", RABookmakerDetail.class));

    private static Path fixtures;
    private static RestDecoder strict;

    @BeforeAll
    static void loadTheSchema() throws Exception {
        Path vendored = vendored();
        fixtures = vendored.resolve("test/fixtures/rest");
        strict = RestDecoder.strict(RestDecoder.DEFAULT_MAX_BYTES, schema(vendored.resolve("schema/rest")));
    }

    @Test
    void everyFixtureDecodesStrictlyToItsClass() throws IOException, DecodeException {
        var decoded = new ArrayList<String>();
        for (var entry : CLASSES.entrySet()) {
            var files = xmlFiles(fixtures.resolve(entry.getKey()));
            // an emptied directory would skip its endpoint without a word
            assertThat(files).as("fixtures of %s", entry.getKey()).isNotEmpty();
            for (Path fixture : files) {
                byte[] body = Files.readAllBytes(fixture);
                assertThat(strict.decode(body, entry.getValue()))
                        .as(fixture.getFileName().toString())
                        .isInstanceOf(entry.getValue());
                assertThat(RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES).decode(body))
                        .as("%s, lenient", fixture.getFileName())
                        .isInstanceOf(entry.getValue());
                decoded.add(entry.getKey() + "/" + fixture.getFileName());
            }
        }
        // a fixture directory that yields nothing would pass the loop above
        assertThat(decoded).as("fixtures decoded").hasSizeGreaterThan(CLASSES.size());
    }

    /**
     * What each fixture decodes to, written back as XML, is the fixture: every element, attribute and
     * text in the same place with the same value. So nothing is dropped, and nothing lands in a field
     * that holds it differently.
     */
    @Test
    void everyFixtureDecodesToWhatItSays() throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
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
        // An endpoint added upstream must be placed here on purpose, not skipped quietly.
        try (Stream<Path> directories = Files.list(fixtures)) {
            assertThat(directories
                            .filter(Files::isDirectory)
                            .map(d -> d.getFileName().toString()))
                    .containsExactlyInAnyOrderElementsOf(CLASSES.keySet());
        }
    }
}
