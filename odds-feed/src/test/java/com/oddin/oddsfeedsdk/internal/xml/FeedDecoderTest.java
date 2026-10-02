package com.oddin.oddsfeedsdk.internal.xml;

import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFAlive;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlementMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFChangeType;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFavourite;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFMarketStatus;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChangeMarket;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOutcomeActive;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the decoder does with input it should not trust. */
class FeedDecoderTest {

    private static final String ALIVE = "<alive product=\"1\" timestamp=\"1777832981632\" subscribed=\"1\"/>";

    private static FeedDecoder strict;
    private final FeedDecoder lenient = FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES);

    @BeforeAll
    static void loadTheSchema() throws Exception {
        strict = FeedDecoder.strict(
                FeedDecoder.DEFAULT_MAX_BYTES,
                XmlFixtures.schema(XmlFixtures.vendored().resolve("schema/feed")));
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsParsed() throws DecodeException {
        byte[] body = bytes(ALIVE);
        assertThat(FeedDecoder.lenient(body.length).decode(body)).isInstanceOf(OFAlive.class);
        // one byte over, and not even XML: refused on its size alone
        assertThatThrownBy(() -> FeedDecoder.lenient(body.length).decode(new byte[body.length + 1]))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("over the limit of " + body.length);
    }

    @Test
    void neitherAnExternalEntityNorAnExternalDtdIsFetched() throws IOException, InterruptedException {
        // A server the parser would call if it resolved anything; nothing may reach it.
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] answer = bytes("<!ENTITY leak \"x\">");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            // the server answers, so the zero below means the parser never called, not that it could not
            try (var client = HttpClient.newHttpClient()) {
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/control")).build(), BodyHandlers.discarding());
            }
            assertThat(requests).as("the control request").hasValue(1);
            String entity = """
                    <?xml version="1.0"?>
                    <!DOCTYPE alive [<!ENTITY secret SYSTEM "%s/entity">]>
                    <alive product="1" timestamp="1" subscribed="1">&secret;</alive>
                    """.formatted(base);
            String dtd = """
                    <?xml version="1.0"?>
                    <!DOCTYPE alive SYSTEM "%s/dtd">
                    <alive product="1" timestamp="1" subscribed="1"/>
                    """.formatted(base);
            for (String xml : List.of(entity, dtd)) {
                try {
                    lenient.decode(bytes(xml));
                } catch (DecodeException refused) {
                    // refusing is fine; fetching is not
                }
            }
        } finally {
            server.stop(0);
        }
        assertThat(requests).as("requests, the control one included").hasValue(1);
    }

    @Test
    void anInternalEntityIsNotExpanded() {
        // harmless if expanded - "1" - so a parser that expands it decodes the message and this fails
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE alive [<!ENTITY one "1">]>
                <alive product="1" timestamp="1" subscribed="&one;"/>
                """;
        assertThatThrownBy(() -> lenient.decode(bytes(xml))).isInstanceOf(DecodeException.class);
    }

    @Test
    void nestingDeeperThanTheLimitIsRefused() throws DecodeException {
        String deep = "<x>".repeat(FeedDecoder.MAX_DEPTH) + "</x>".repeat(FeedDecoder.MAX_DEPTH);
        assertThatThrownBy(() -> lenient.decode(
                        bytes("<alive product=\"1\" timestamp=\"1\" subscribed=\"1\">" + deep + "</alive>")))
                .isInstanceOf(DecodeException.class);
        // the same content, shallow, is only skipped
        assertThat(lenient.decode(bytes("<alive product=\"1\" timestamp=\"1\" subscribed=\"1\"><x/></alive>")))
                .isInstanceOf(OFAlive.class);
    }

    private static final String ROOT = "<alive product=\"1\" timestamp=\"1\" subscribed=\"1\">";

    @Test
    void manyNamesBuiltToShareAHashAreRefusedQuickly() {
        // every concatenation of "Aa" and "BB" has the same String hash: 2^13 such names, as siblings
        var names = new ArrayList<String>();
        colliding("", 13, names);
        var body = new StringBuilder("<alive product=\"1\" timestamp=\"1\" subscribed=\"1\">");
        names.forEach(name -> body.append('<').append(name).append("/>"));
        body.append("</alive>");
        long started = System.nanoTime();
        assertThatThrownBy(() -> lenient.decode(bytes(body.toString())))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("distinct names");
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("refused before the colliding names add up")
                .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void collidingAttributeNamesPrefixesNamespacesAndInstructionsAreRefusedToo() {
        var names = new ArrayList<String>();
        colliding("", 13, names);
        var attributes = new StringBuilder(ROOT);
        for (int element = 0; element < 10; element++) {
            attributes.append("<x");
            for (int i = 0; i < 60; i++) {
                attributes.append(' ').append(names.get(element * 60 + i)).append("=\"1\"");
            }
            attributes.append("/>");
        }
        var prefixes = new StringBuilder(ROOT);
        var uris = new StringBuilder(ROOT);
        var declaredUris = new StringBuilder(ROOT);
        for (String name : names) {
            prefixes.append("<x xmlns:").append(name).append("=\"u\"/>");
            uris.append("<x xmlns=\"").append(name).append("\"/>");
            // declared, but not the element's own: only the declaration names it
            declaredUris.append("<x xmlns:p=\"").append(name).append("\"/>");
        }
        for (String hostile : List.of(
                attributes.append("</alive>").toString(),
                prefixes.append("</alive>").toString(),
                uris.append("</alive>").toString(),
                declaredUris.append("</alive>").toString())) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> lenient.decode(bytes(hostile))).isInstanceOf(DecodeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("refused before the colliding names add up")
                    .isLessThan(Duration.ofSeconds(1));
        }
        // a single start tag is read whole before any limit on names sees it: 2^15 colliding declarations
        var many = new ArrayList<String>();
        colliding("", 15, many);
        var oneTag = new StringBuilder("<producers response_code=\"OK\"");
        many.forEach(name -> oneTag.append(" xmlns:").append(name).append("=\"u\""));
        String tag = oneTag.append("/>").toString();
        var rest = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES);
        long started = System.nanoTime();
        assertThatThrownBy(() -> rest.decode(bytes(tag))).isInstanceOf(DecodeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("stopped by the attribute limit as the tag is read")
                .isLessThan(Duration.ofSeconds(1));

        assertThatThrownBy(() -> lenient.decode(bytes(ALIVE + "<?" + names.getFirst() + " x?>")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("processing instruction");
    }

    @Test
    void anElementMayCarrySixtyFourAttributesAndNoMore() throws DecodeException {
        assertThat(lenient.decode(bytes(ROOT + attributes(XmlReader.MAX_ATTRIBUTES) + "</alive>")))
                .isInstanceOf(OFAlive.class);
        assertThatThrownBy(() -> lenient.decode(bytes(ROOT + attributes(XmlReader.MAX_ATTRIBUTES + 1) + "</alive>")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining(String.valueOf(XmlReader.MAX_ATTRIBUTES));
    }

    @Test
    void movingByTagOrReadingTextGoesThroughTheLimitToo() throws Exception {
        var names = new ArrayList<String>();
        colliding("", 10, names);
        var body = new StringBuilder("<r>");
        names.forEach(name -> body.append('<').append(name).append("/>"));
        String hostile = body.append("</r>").toString();
        var inputs = javax.xml.stream.XMLInputFactory.newDefaultFactory();
        var byTag = new XmlReader.NameLimit(inputs.createXMLStreamReader(new java.io.StringReader(hostile)));
        assertThatThrownBy(() -> {
                    while (byTag.hasNext()) {
                        byTag.nextTag();
                    }
                })
                .isInstanceOf(javax.xml.stream.XMLStreamException.class)
                .hasMessageContaining("distinct names");
        var byText =
                new XmlReader.NameLimit(inputs.createXMLStreamReader(new java.io.StringReader("<r>a<?x y?>b</r>")));
        byText.nextTag();
        assertThatThrownBy(byText::getElementText).hasMessageContaining("processing instruction");
    }

    @Test
    void longNamesLongValuesAndNamesSharingAHashAreRefused() throws DecodeException {
        String longName = "n".repeat(XmlReader.MAX_NAME_LENGTH + 1);
        assertThatThrownBy(() -> lenient.decode(bytes(ROOT + "<" + longName + "/></alive>")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("longer than");
        String longUri = "u".repeat(XmlReader.MAX_ATTRIBUTE_LENGTH + 1);
        assertThatThrownBy(() -> lenient.decode(bytes(ROOT + "<x xmlns:p=\"" + longUri + "\"/></alive>")))
                .isInstanceOf(DecodeException.class);
        // a long namespace within the limit is fine: only names are held to their length
        String uri = "urn:" + "u".repeat(200);
        assertThat(lenient.decode(bytes(ROOT + "<x xmlns:p=\"" + uri + "\"/></alive>")))
                .isInstanceOf(OFAlive.class);

        // repeated, a few colliding names never pass the count of distinct names, but share one hash
        var names = new ArrayList<String>();
        colliding("", 6, names);
        var repeated = new StringBuilder(ROOT);
        for (int i = 0; i < 100; i++) {
            for (String name : names) {
                repeated.append('<').append(name).append("/>");
            }
        }
        assertThatThrownBy(
                        () -> lenient.decode(bytes(repeated.append("</alive>").toString())))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("with one hash");
    }

    @Test
    void aDocumentMayUseExactlyTheMostNamesWithOneHash() throws DecodeException {
        var names = new ArrayList<String>();
        colliding("", 4, names);
        var fits = new StringBuilder(ROOT);
        names.subList(0, XmlReader.MAX_NAMES_PER_HASH)
                .forEach(name -> fits.append('<').append(name).append("/>"));
        assertThat(lenient.decode(bytes(fits.append("</alive>").toString()))).isInstanceOf(OFAlive.class);
        var over = new StringBuilder(ROOT);
        names.subList(0, XmlReader.MAX_NAMES_PER_HASH + 1)
                .forEach(name -> over.append('<').append(name).append("/>"));
        assertThatThrownBy(() -> lenient.decode(bytes(over.append("</alive>").toString())))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("more than " + XmlReader.MAX_NAMES_PER_HASH + " distinct names with one hash");
    }

    /**
     * What the cap per hash rests on, pinned against Woodstox itself: it seeds its hash, so only
     * names whose hashes are equal whatever the seed can be made to share a chain, and names of one
     * length and first character with equal {@code String} hashes are. An upgrade that drops the
     * seed, or hashes otherwise, fails here.
     */
    @Test
    void woodstoxSeedsItsHashButNamesSharingAStringHashCollideInItWhateverTheSeed() {
        var names = new ArrayList<String>();
        colliding("", 4, names);
        var seeds = new java.util.HashSet<Integer>();
        for (int table = 0; table < 8; table++) {
            int seed = new com.ctc.wstx.util.SymbolTable(true, 128).getHashSeed();
            seeds.add(seed);
            assertThat(names.stream().map(name -> com.ctc.wstx.util.SymbolTable.calcHash(name, seed)))
                    .as("seed %d", seed)
                    .containsOnly(com.ctc.wstx.util.SymbolTable.calcHash(names.getFirst(), seed));
        }
        assertThat(seeds).as("a seed for each table").hasSizeGreaterThan(1);
        // its finalizer is not linear: names of one length and first character whose String hashes
        // differ but share their low bits - as many as a document may use, built for a table of
        // 4096 - spread over its buckets instead of sharing one
        var sharingLowBits = new ArrayList<String>();
        for (int i = 0; sharingLowBits.size() < XmlReader.MAX_NAMES; i++) {
            String name = "q" + (10_000_000 + i);
            if ((name.hashCode() & 4095) == 0) {
                sharingLowBits.add(name);
            }
        }
        int seed = new com.ctc.wstx.util.SymbolTable(true, 128).getHashSeed();
        assertThat(sharingLowBits.stream()
                        .map(name -> com.ctc.wstx.util.SymbolTable.calcHash(name, seed) & 4095)
                        .distinct()
                        .count())
                .isGreaterThan(XmlReader.MAX_NAMES / 2);
    }

    @Test
    void attributesAndDeclarationsTogetherAreHeldToTheMostAnElementMayCarry() throws DecodeException {
        int most = XmlReader.MAX_ATTRIBUTES;
        assertThat(lenient.decode(bytes(ROOT + mixed(most - 1, 1) + "</alive>")))
                .isInstanceOf(OFAlive.class);
        assertThat(lenient.decode(bytes(ROOT + mixed(1, most - 1) + "</alive>")))
                .isInstanceOf(OFAlive.class);
        // Woodstox checks the two kinds' sum only as one of its arrays grows: these pass it
        for (String hostile : List.of(mixed(most - 1, 2), mixed(1, most))) {
            assertThatThrownBy(() -> lenient.decode(bytes(ROOT + hostile + "</alive>")))
                    .isInstanceOf(DecodeException.class)
                    .hasMessageContaining("more than " + most + " attributes and namespace declarations");
        }
    }

    @Test
    void aDocumentMayUseExactlyTheMostDistinctNames() throws DecodeException {
        // the root's name and its three attributes count too
        int room = XmlReader.MAX_NAMES - 4;
        assertThat(lenient.decode(bytes(ROOT + spread(room) + "</alive>"))).isInstanceOf(OFAlive.class);
        assertThatThrownBy(() -> lenient.decode(bytes(ROOT + spread(room + 1) + "</alive>")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("more than " + XmlReader.MAX_NAMES + " distinct names");
    }

    @Test
    void attributeNamesAndPrefixesAreHeldToTheLengthToo() throws DecodeException {
        String fits = "a".repeat(XmlReader.MAX_NAME_LENGTH);
        String over = "a".repeat(XmlReader.MAX_NAME_LENGTH + 1);
        assertThat(lenient.decode(bytes(ROOT + "<x " + fits + "=\"1\"/></alive>")))
                .isInstanceOf(OFAlive.class);
        assertThat(lenient.decode(bytes(ROOT + "<x xmlns:" + fits + "=\"u\"/></alive>")))
                .isInstanceOf(OFAlive.class);
        for (String hostile :
                List.of(ROOT + "<x " + over + "=\"1\"/></alive>", ROOT + "<x xmlns:" + over + "=\"u\"/></alive>")) {
            assertThatThrownBy(() -> lenient.decode(bytes(hostile)))
                    .isInstanceOf(DecodeException.class)
                    .hasMessageContaining("longer than");
        }
    }

    @Test
    void namespaceDeclarationsCountTowardsAnElementsAttributes() throws DecodeException {
        assertThat(lenient.decode(bytes(ROOT + declarations(XmlReader.MAX_ATTRIBUTES) + "</alive>")))
                .isInstanceOf(OFAlive.class);
        assertThatThrownBy(() -> lenient.decode(bytes(ROOT + declarations(XmlReader.MAX_ATTRIBUTES + 1) + "</alive>")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining(String.valueOf(XmlReader.MAX_ATTRIBUTES));
    }

    /** Distinct names that collide in no chain more than they must. */
    private static String spread(int count) {
        var body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append("<s").append(i).append("/>");
        }
        return body.toString();
    }

    private static String mixed(int attributes, int declarations) {
        var tag = new StringBuilder("<x");
        for (int i = 0; i < attributes; i++) {
            tag.append(" a").append(i).append("=\"1\"");
        }
        for (int i = 0; i < declarations; i++) {
            tag.append(" xmlns:p").append(i).append("=\"u").append(i).append('"');
        }
        return tag.append("/>").toString();
    }

    private static String declarations(int count) {
        var tag = new StringBuilder("<x");
        for (int i = 0; i < count; i++) {
            tag.append(" xmlns:p").append(i).append("=\"u").append(i).append('"');
        }
        return tag.append("/>").toString();
    }

    private static String attributes(int count) {
        var tag = new StringBuilder("<x");
        for (int i = 0; i < count; i++) {
            tag.append(" a").append(i).append("=\"1\"");
        }
        return tag.append("/>").toString();
    }

    /**
     * Woodstox's own limits, on its reader as the decoder configures it but without the name limit
     * in front: they bound what one start tag holds before anything after the parser sees it.
     */
    @Test
    void theParserItselfHoldsAStartTagToItsLimits() throws Exception {
        var inputs = new XmlReader(
                        XmlReader.context(com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory.class),
                        "message",
                        "feed message",
                        FeedDecoder.DEFAULT_MAX_BYTES,
                        null)
                .inputs();
        String value = "v".repeat(XmlReader.MAX_ATTRIBUTE_LENGTH);
        for (String fits : List.of(
                attributes(XmlReader.MAX_ATTRIBUTES),
                declarations(XmlReader.MAX_ATTRIBUTES),
                "<x a=\"" + value + "\"/>",
                "<x xmlns:p=\"" + value + "\"/>")) {
            readThrough(inputs, fits);
        }
        for (String over :
                List.of(attributes(XmlReader.MAX_ATTRIBUTES + 1), declarations(XmlReader.MAX_ATTRIBUTES + 1))) {
            assertThatThrownBy(() -> readThrough(inputs, over))
                    .isInstanceOf(javax.xml.stream.XMLStreamException.class)
                    .hasMessageContaining("Attribute limit (" + XmlReader.MAX_ATTRIBUTES + ")");
        }
        for (String over : List.of("<x a=\"" + value + "v\"/>", "<x xmlns:p=\"" + value + "v\"/>")) {
            assertThatThrownBy(() -> readThrough(inputs, over))
                    .isInstanceOf(javax.xml.stream.XMLStreamException.class)
                    .hasMessageContaining(String.valueOf(XmlReader.MAX_ATTRIBUTE_LENGTH));
        }
    }

    private static void readThrough(javax.xml.stream.XMLInputFactory inputs, String document)
            throws javax.xml.stream.XMLStreamException {
        var reader = inputs.createXMLStreamReader(new java.io.StringReader(document));
        while (reader.hasNext()) {
            reader.next();
        }
    }

    @Test
    void namesStayWithTheirDocument() throws Exception {
        var reader = new XmlReader(
                XmlReader.context(com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory.class),
                "message",
                "feed message",
                FeedDecoder.DEFAULT_MAX_BYTES,
                null);
        var shared = com.ctc.wstx.stax.WstxInputFactory.class.getDeclaredField("mSymbols");
        shared.setAccessible(true);
        int before = ((com.ctc.wstx.util.SymbolTable) shared.get(reader.inputs())).size();
        for (int document = 0; document < 4; document++) {
            var body = new StringBuilder(ROOT);
            for (int i = 0; i < 400; i++) {
                body.append("<d").append(document).append('n').append(i).append("/>");
            }
            reader.read(bytes(body.append("</alive>").toString()));
        }
        assertThat(((com.ctc.wstx.util.SymbolTable) shared.get(reader.inputs())).size())
                .as("no document's names left behind for the next")
                .isEqualTo(before);
    }

    private static void colliding(String prefix, int blocks, List<String> into) {
        if (blocks == 0) {
            into.add("q" + prefix);
            return;
        }
        colliding(prefix + "Aa", blocks - 1, into);
        colliding(prefix + "BB", blocks - 1, into);
    }

    @Test
    void aBodyThatIsNotWellFormedIsRefused() {
        assertThatThrownBy(() -> lenient.decode(bytes("<alive product=\"1\"")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("not a well-formed feed message");
    }

    @Test
    void malformedContentAfterTheMessageIsRefused() {
        assertThatThrownBy(() -> lenient.decode(bytes(ALIVE + "<unclosed")))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("not a well-formed feed message");
    }

    @Test
    void aDocumentThatIsNotAFeedMessageIsRefused() {
        assertThatThrownBy(() -> lenient.decode(bytes("<producers response_code=\"OK\"/>")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void anAttributeTheClassesDoNotKnowIsSkippedAndOnlyStrictDecodingFailsOnIt() throws DecodeException {
        // JAXB never reports an unknown attribute; only validating against the schema finds it
        String xml = "<alive product=\"1\" timestamp=\"1777832981632\" subscribed=\"1\" brand_new=\"x\"/>";
        assertThat(((OFAlive) lenient.decode(bytes(xml))).getSubscribed()).isEqualTo(1);
        assertThatThrownBy(() -> strict.decode(bytes(xml)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("brand_new");
    }

    @Test
    void anElementTheClassesDoNotKnowIsSkippedAndOnlyStrictDecodingFailsOnIt() throws DecodeException {
        String xml = """
                <alive product="1" timestamp="1777832981632" subscribed="1">
                  <brand_new_element/>
                </alive>
                """;
        assertThat(((OFAlive) lenient.decode(bytes(xml))).getSubscribed()).isEqualTo(1);
        assertThatThrownBy(() -> strict.decode(bytes(xml)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("brand_new_element");
    }

    @Test
    void anEnumValueThisSdkDoesNotKnowReadsAsUnknownAndKeepsItsNumber() throws DecodeException {
        String xml = """
                <odds_change product="2" event_id="od:match:1" timestamp="1">
                  <odds><market id="1" status="-9"/></odds>
                </odds_change>
                """;
        OFOddsChangeMarket market = ((OFOddsChange) lenient.decode(bytes(xml)))
                .getOdds()
                .getMarket()
                .getFirst();
        assertThat(market.getStatus()).isEqualTo(OFMarketStatus.UNKNOWN);
        assertThat(market.getStatusRaw()).isEqualTo(-9);
        assertThatThrownBy(() -> strict.decode(bytes(xml))).isInstanceOf(DecodeException.class);
    }

    @Test
    void aRequiredEnumAMessageLacksReadsAsAbsentNotAsAValue() throws DecodeException {
        // a settlement outcome without its result must not read as LOST, nor a status as NOT_STARTED
        var settlement = (OFBetSettlement) lenient.decode(bytes("""
                <bet_settlement product="2" event_id="od:match:1" timestamp="1">
                  <outcomes><market id="1"><outcome id="1"/></market></outcomes>
                </bet_settlement>
                """));
        var outcome =
                settlement.getOutcomes().getMarket().getFirst().getOutcome().getFirst();
        assertThat(outcome.getResult()).isNull();
        assertThat(outcome.getResultRaw()).isNull();
        outcome.setResult(null);
        var change = (OFOddsChange) lenient.decode(bytes("""
                <odds_change product="2" event_id="od:match:1" timestamp="1"><sport_event_status/></odds_change>
                """));
        assertThat(change.getSportEventStatus().getStatus()).isNull();
    }

    @Test
    void everyEnumAccessorReadsWhatTheFixtureCarries() throws Exception {
        Path feed = XmlFixtures.vendored().resolve("test/fixtures/feed");
        var betStop = (OFBetStop) lenient.decode(Files.readAllBytes(feed.resolve("bet_stop/bet_stop_all_groups.xml")));
        assertThat(betStop.getMarketStatus()).isEqualTo(OFMarketStatus.SUSPENDED);
        var fixtureChange =
                (OFFixtureChange) lenient.decode(Files.readAllBytes(feed.resolve("fixture_change/fixture_change.xml")));
        assertThat(fixtureChange.getChangeType()).isEqualTo(OFChangeType.NEW);
        var settlement =
                (OFBetSettlement) lenient.decode(Files.readAllBytes(feed.resolve("bet_settlement/bet_settlement.xml")));
        assertThat(settlement.getOutcomes().getMarket().getFirst().getOutcome())
                .extracting(OFBetSettlementMarket.OFOutcome::getResult)
                .containsExactly(OFResult.WON, OFResult.LOST);
        var odds = (OFOddsChange)
                lenient.decode(Files.readAllBytes(feed.resolve("odds_change/odds_change_markets_only.xml")));
        OFOddsChangeMarket market = odds.getOdds().getMarket().getFirst();
        assertThat(market.getFavourite()).isEqualTo(OFFavourite.YES);
        // this market has no specifiers: empty, as in 0.0.x, and the raw value says there were none
        assertThat(market.getSpecifiers()).isEmpty();
        assertThat(market.getSpecifiersRaw()).isNull();
        assertThat(market.getOutcome().getFirst().getActive()).isEqualTo(OFOutcomeActive.ACTIVE);
        var closed = (OFOddsChange)
                lenient.decode(Files.readAllBytes(feed.resolve("odds_change/odds_change_closed_with_winner.xml")));
        assertThat(closed.getSportEventStatus().getStatus()).isEqualTo(OFEventStatus.FINALIZED);
    }

    @Test
    void theEnumAccessorsReadAndWriteTheNumber() {
        var market = new OFOddsChangeMarket();
        assertThat(market.getStatus()).as("no status").isNull();
        market.setStatus(OFMarketStatus.SUSPENDED);
        assertThat(market.getStatusRaw()).isEqualTo(-1);
        market.setStatusRaw(-3);
        assertThat(market.getStatus()).isEqualTo(OFMarketStatus.SETTLED);
        market.setStatus(null);
        assertThat(market.getStatusRaw()).isNull();
    }

    @Test
    void theEnumsKeepTheirZeroZeroXContract() {
        assertThat(OFMarketStatus.fromValue(-4)).isEqualTo(OFMarketStatus.CANCELLED);
        assertThat(OFMarketStatus.CANCELLED.value()).isEqualTo(-4);
        // 0.0.x threw for a number without a constant, and UNKNOWN is not one
        assertThatThrownBy(() -> OFMarketStatus.fromValue(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(OFMarketStatus.UNKNOWN::value).isInstanceOf(IllegalStateException.class);
    }
}
