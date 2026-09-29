package com.oddin.oddsfeedsdk.internal.xml;

import static com.oddin.oddsfeedsdk.internal.xml.XmlFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.api.ResponseWithCode;
import com.oddin.oddsfeedsdk.cache.StaticData;
import com.oddin.oddsfeedsdk.schema.rest.v1.RACompetitorProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAError;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchStatusDescriptions;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMatchSummaryEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayer;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayerProfileEndpoint;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAProducers;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAResponseCode;
import com.oddin.oddsfeedsdk.schema.rest.v1.RASportEventStatus;
import com.oddin.oddsfeedsdk.schema.rest.v1.RATeamable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the REST decoder does with what it is given, and the accessors kept for 0.0.x callers. */
class RestDecoderTest {

    private static final String PRODUCERS = "<producers response_code=\"OK\"/>";

    private static Path fixtures;
    private static RestDecoder strict;
    private final RestDecoder lenient = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES);

    @BeforeAll
    static void loadTheSchema() throws Exception {
        fixtures = XmlFixtures.vendored().resolve("test/fixtures/rest");
        strict = RestDecoder.strict(
                RestDecoder.DEFAULT_MAX_BYTES,
                XmlFixtures.schema(XmlFixtures.vendored().resolve("schema/rest")));
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsParsed() throws DecodeException {
        byte[] body = bytes(PRODUCERS);
        assertThat(RestDecoder.lenient(body.length).decode(body)).isInstanceOf(RAProducers.class);
        assertThatThrownBy(() -> RestDecoder.lenient(body.length).decode(new byte[body.length + 1]))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("over the limit of " + body.length);
    }

    @Test
    void anInternalEntityIsNotExpanded() {
        // the REST path reads through the feed's hardened parser: harmless if expanded - "OK" - so a
        // parser that expands it decodes the response and this fails
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE producers [<!ENTITY ok "OK">]>
                <producers response_code="&ok;"/>
                """;
        assertThatThrownBy(() -> lenient.decode(bytes(xml))).isInstanceOf(DecodeException.class);
    }

    @Test
    void aResponseOfAnotherTypeIsRefused() {
        assertThatThrownBy(() -> lenient.decode(bytes(PRODUCERS), RAMatchStatusDescriptions.class))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("RAMatchStatusDescriptions")
                .hasMessageContaining("RAProducers");
    }

    @Test
    void aRootOfANamedTypeDecodesToTheTypeNotToAJaxbElement() throws Exception {
        assertThat(lenient.decode(fixture("match_summary/match_summary.xml")))
                .isInstanceOf(RAMatchSummaryEndpoint.class);
    }

    @Test
    void anAttributeTheClassesDoNotKnowIsSkippedAndOnlyStrictDecodingFailsOnIt() throws DecodeException {
        String xml = "<producers response_code=\"OK\" brand_new=\"x\"/>";
        assertThat(lenient.decode(bytes(xml), RAProducers.class).getResponseCode())
                .isEqualTo(RAResponseCode.OK);
        assertThatThrownBy(() -> strict.decode(bytes(xml)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("brand_new");
    }

    @Test
    void anElementTheClassesDoNotKnowIsSkippedAndOnlyStrictDecodingFailsOnIt() throws DecodeException {
        String xml = "<producers response_code=\"OK\"><brand_new_element/></producers>";
        assertThat(lenient.decode(bytes(xml), RAProducers.class).getProducer()).isEmpty();
        assertThatThrownBy(() -> strict.decode(bytes(xml)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("brand_new_element");
    }

    @Test
    void anErrorKeepsItsTextsAndReadsItsCode() throws Exception {
        RAError error = strict.decode(fixture("error/not_found.xml"), RAError.class);
        assertThat(error.getResponseCode()).isEqualTo(RAResponseCode.NOT_FOUND);
        assertThat(error.getResponseCodeRaw()).isEqualTo("NOT_FOUND");
        assertThat(error.getAction()).isEqualTo("Invalid market ID.. Not Found");
        assertThat(error.getMessage()).isEqualTo("ERROR. Invalid market ID.. Not Found");
        assertThat((Object) error).isInstanceOf(ResponseWithCode.class);
    }

    @Test
    void anErrorCodeThisSdkDoesNotKnowReadsAsNullAndKeepsItsText() throws DecodeException {
        // the schema leaves the error's code open: the API can send any status text
        RAError error = lenient.decode(bytes("<response response_code=\"TOO_MANY_REQUESTS\"/>"), RAError.class);
        assertThat(error.getResponseCode()).isNull();
        assertThat(error.getResponseCodeRaw()).isEqualTo("TOO_MANY_REQUESTS");
        error.setResponseCode(RAResponseCode.FORBIDDEN);
        assertThat(error.getResponseCodeRaw()).isEqualTo("FORBIDDEN");
    }

    @Test
    void aCompetitorProfileListsItsPlayersAsZeroZeroXDid() throws Exception {
        var profile = strict.decode(fixture("competitor/competitor_profile.xml"), RACompetitorProfileEndpoint.class);
        assertThat(profile.getPlayers()).extracting(RAPlayer::getName).containsExactly("Player One");
        assertThat((Object) profile).isInstanceOf(RATeamable.class);
        assertThat(profile.getCompetitor()).isInstanceOf(RATeamable.class);
        assertThat(profile.getCompetitor().getVirtual()).isFalse();
        assertThat(profile.getCompetitor().isVirtual()).isFalse();

        var empty = strict.decode(
                fixture("competitor/competitor_profile_no_players.xml"), RACompetitorProfileEndpoint.class);
        assertThat(empty.getPlayers()).isEmpty();

        var player = new RAPlayer();
        player.setName("Player Two");
        empty.setPlayers(List.of(player));
        assertThat(empty.getPlayersElement().getPlayer()).containsExactly(player);
    }

    @Test
    void aPlayerProfileKeepsItsGenerationTimeAsText() throws Exception {
        var profile = strict.decode(fixture("player/player_profile.xml"), RAPlayerProfileEndpoint.class);
        assertThat(profile.getGeneratedAt()).isEqualTo("2026-08-26T12:00:00");
        assertThat(profile.getPlayer().getUnderage()).isEqualTo(1);
        profile.setGeneratedAt("2026-08-27T08:30:00");
        assertThat(profile.getGeneratedAtRaw().getDay()).isEqualTo(27);
        profile.setGeneratedAt(null);
        assertThat(profile.getGeneratedAt()).isNull();
    }

    @Test
    void aMissingScoreboardFlagReadsAsNoScoreboard() throws Exception {
        RASportEventStatus status = strict.decode(
                        fixture("match_summary/match_summary.xml"), RAMatchSummaryEndpoint.class)
                .getSportEventStatus();
        assertThat(status.isScoreboardAvailable()).isTrue();
        status.setScoreboardAvailableRaw(null);
        assertThat(status.isScoreboardAvailable()).isFalse();
        status.setScoreboardAvailable(true);
        assertThat(status.getScoreboardAvailableRaw()).isTrue();
    }

    @Test
    void theStartTimeFlagReadsTheSameThroughBothGetters() throws Exception {
        var summary = strict.decode(fixture("match_summary/match_summary.xml"), RAMatchSummaryEndpoint.class);
        assertThat(summary.getSportEvent().isStartTimeTbd())
                .isEqualTo(summary.getSportEvent().getStartTimeTbd());
    }

    @Test
    void matchStatusDescriptionsAreStaticData() throws Exception {
        var descriptions =
                strict.decode(fixture("match_status/match_status_descriptions.xml"), RAMatchStatusDescriptions.class);
        assertThat(descriptions.getResponseCode()).isEqualTo(RAResponseCode.OK);
        assertThat(descriptions.getMatchStatus())
                .extracting(StaticData::getId, StaticData::getDescription)
                .startsWith(org.assertj.core.groups.Tuple.tuple(0L, "Not started"));
    }

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(fixtures.resolve(name));
    }
}
