package com.oddin.oddsfeedsdk.internal.replay;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.oddin.oddsfeed.fakes.FakeRestServer;
import com.oddin.oddsfeed.fakes.RecordedRequest;
import com.oddin.oddsfeedsdk.LogCapture;
import com.oddin.oddsfeedsdk.OddsFeed;
import com.oddin.oddsfeedsdk.api.entities.sportevent.LiveOddsAvailability;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedUrnFormatException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.ApiEvents;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The replay manager against the fake API: what each call sends, and 0.0.x's answers. */
class ReplayTest {

    private static final URN MATCH = URN.parse("od:match:1");
    private static final String EVENT_PATH = "/v1/replay/events/od:match:1";
    private static final String LIST_PATH = "/v1/replay";
    private static final String PLAY_PATH = "/v1/replay/play";
    private static final String STATUS_PATH = "/v1/replay/status";
    private static final String NOT_FOUND = """
            <response response_code="NOT_FOUND"><action>replay</action><message>no such thing</message></response>""";
    private static final String BROKEN = """
            <response response_code="INTERNAL_SERVER_ERROR"><action>replay</action><message>broken</message></response>""";

    private final FakeRestServer api = FakeRestServer.start();
    private final ApiClient client;
    private final Built built = new Built();

    ReplayTest() {
        client = new ApiClient(
                OddsFeed.getOddsFeedConfigurationBuilder()
                        .selectEnvironment("mq.invalid", api.apiHost())
                        .setAccessToken("token")
                        .setSDKNodeId(7)
                        .build(),
                ApiEvents.NONE);
    }

    @AfterEach
    void close() {
        client.close();
        api.close();
    }

    @Test
    void eachControlCallSendsWhatZeroZeroXSentAndAnswersTrue() {
        Replay replay = replay(ExceptionHandlingStrategy.THROW);

        assertThat(replay.addSportEvent(MATCH)).as("adding by id").isTrue();
        assertThat(replay.addSportEvent(new Event(URN.parse("od:match:2"))))
                .as("adding an event")
                .isTrue();
        assertThat(replay.removeSportEvent(MATCH)).as("removing by id").isTrue();
        assertThat(replay.removeSportEvent(new Event(URN.parse("od:match:2"))))
                .as("removing an event")
                .isTrue();
        assertThat(replay.stop()).as("stopping").isTrue();
        assertThat(replay.clear()).as("clearing").isTrue();

        assertThat(sent())
                .containsExactly(
                        "PUT /v1/replay/events/od:match:1?node_id=7",
                        "PUT /v1/replay/events/od:match:2?node_id=7",
                        "DELETE /v1/replay/events/od:match:1?node_id=7",
                        "DELETE /v1/replay/events/od:match:2?node_id=7",
                        "POST /v1/replay/stop?node_id=7",
                        "POST /v1/replay/clear?node_id=7");
    }

    @Test
    void eachPlaySendsItsOwnParametersAndLeavesTheRestToTheApi() {
        Replay replay = replay(ExceptionHandlingStrategy.THROW);

        assertThat(replay.play()).isTrue();
        assertThat(replay.play(30, 500)).isTrue();
        assertThat(replay.play(30, 500, true)).isTrue();
        assertThat(replay.play(30, 500, false)).isTrue();
        assertThat(replay.play(20, 100, "2", false)).isTrue();
        assertThat(replay.play(20, 100, "2", true)).isTrue();
        assertThat(replay.play(10, 50, "1", true, false)).isTrue();
        assertThat(replay.play(10, 50, "1", false, true)).isTrue();
        assertThat(replay.play(5, 0, "2", true, true)).isTrue();

        assertThat(sent())
                .containsExactly(
                        "POST /v1/replay/play?node_id=7",
                        "POST /v1/replay/play?node_id=7&speed=30&max_delay=500",
                        "POST /v1/replay/play?node_id=7&speed=30&max_delay=500&run_parallel=true",
                        "POST /v1/replay/play?node_id=7&speed=30&max_delay=500&run_parallel=false",
                        "POST /v1/replay/play?node_id=7&speed=20&max_delay=100&use_replay_timestamp=false&product=2",
                        "POST /v1/replay/play?node_id=7&speed=20&max_delay=100&use_replay_timestamp=true&product=2",
                        "POST /v1/replay/play?node_id=7&speed=10&max_delay=50&use_replay_timestamp=true"
                                + "&run_parallel=false&product=1",
                        "POST /v1/replay/play?node_id=7&speed=10&max_delay=50&use_replay_timestamp=false"
                                + "&run_parallel=true&product=1",
                        "POST /v1/replay/play?node_id=7&speed=5&max_delay=0&use_replay_timestamp=true"
                                + "&run_parallel=true&product=2");
    }

    @Test
    void anEventWithoutAnIdIsNeitherAddedNorRemoved() {
        Replay replay = replay(ExceptionHandlingStrategy.THROW);

        assertThat(replay.addSportEvent(new Event(null))).isFalse();
        assertThat(replay.removeSportEvent(new Event(null))).isFalse();
        assertThat(api.requests()).as("nothing asked of the API").isEmpty();
    }

    /** 0.0.x caught every failure of a control call, whatever the strategy, and answered false. */
    @ParameterizedTest
    @EnumSource(ExceptionHandlingStrategy.class)
    void aControlCallTheApiFailsAnswersFalseUnderEitherStrategy(ExceptionHandlingStrategy strategy) {
        Replay replay = replay(strategy);
        for (String path : List.of(EVENT_PATH, PLAY_PATH, "/v1/replay/stop", "/v1/replay/clear")) {
            api.respond(path, 500, BROKEN);
        }

        assertThat(replay.addSportEvent(MATCH)).as("adding").isFalse();
        assertThat(replay.addSportEvent(new Event(MATCH))).as("adding an event").isFalse();
        assertThat(replay.removeSportEvent(MATCH)).as("removing").isFalse();
        assertThat(replay.removeSportEvent(new Event(MATCH)))
                .as("removing an event")
                .isFalse();
        assertThat(replay.play()).as("playing").isFalse();
        assertThat(replay.play(30, 500)).as("playing at a speed").isFalse();
        assertThat(replay.play(30, 500, true)).as("playing in parallel").isFalse();
        assertThat(replay.play(30, 500, "1", true)).as("playing one producer").isFalse();
        assertThat(replay.play(30, 500, "1", true, true))
                .as("playing with everything")
                .isFalse();
        assertThat(replay.stop()).as("stopping").isFalse();
        assertThat(replay.clear()).as("clearing").isFalse();

        assertThat(api.requests())
                .as("each asked once: a control call is not repeated after a server error")
                .hasSize(11);
    }

    @ParameterizedTest
    @EnumSource(ExceptionHandlingStrategy.class)
    void theListHoldsTheFactorysEventsInTheApisOrder(ExceptionHandlingStrategy strategy) {
        api.respond(LIST_PATH, 200, list("od:match:3", "od:match:1", "od:match:2"));
        Replay replay = replay(strategy);

        List<SportEvent> events = replay.getReplayList();

        assertThat(events)
                .extracting(SportEvent::getId)
                .containsExactly(URN.parse("od:match:3"), MATCH, URN.parse("od:match:2"));
        assertThat(events).as("the factory's own events").containsExactlyElementsOf(built.events);
        assertThat(sent()).containsExactly("GET /v1/replay?node_id=7");
    }

    @Test
    void theListIsTheFixturesEventByDefault() {
        assertThat(replay(ExceptionHandlingStrategy.THROW).getReplayList())
                .extracting(SportEvent::getId)
                .containsExactly(URN.parse("od:match:198314"));
    }

    @Test
    void anEntryWithoutAnIdIsLeftOut() {
        api.respond(LIST_PATH, 200, """
                <replay_set_content>
                    <replay_event id="od:match:1" position="0"/>
                    <replay_event position="1"/>
                    <replay_event id="od:match:2" position="2"/>
                </replay_set_content>""");

        assertThat(replay(ExceptionHandlingStrategy.THROW).getReplayList())
                .extracting(SportEvent::getId)
                .containsExactly(MATCH, URN.parse("od:match:2"));
    }

    @Test
    void anEmptyListIsEmptyNotNull() {
        api.respond(LIST_PATH, 200, "<replay_set_content/>");

        assertThat(replay(ExceptionHandlingStrategy.THROW).getReplayList())
                .isNotNull()
                .isEmpty();
    }

    @Test
    void eachCallGetsANewList() {
        Replay replay = replay(ExceptionHandlingStrategy.THROW);
        List<SportEvent> first = requireNonNull(replay.getReplayList());

        first.clear();
        assertThat(replay.getReplayList())
                .as("a list the client can change, as 0.0.x's")
                .hasSize(1);
    }

    /** 0.0.x caught a failed fetch of the list, whatever the strategy, and answered null. */
    @ParameterizedTest
    @EnumSource(ExceptionHandlingStrategy.class)
    void aListTheApiFailsIsNullUnderEitherStrategy(ExceptionHandlingStrategy strategy) {
        api.respond(LIST_PATH, 404, NOT_FOUND);

        assertThat(replay(strategy).getReplayList()).isNull();
        assertThat(built.events).as("nothing built").isEmpty();
    }

    @Test
    void theStatusIsTheApisWordForThisNode() {
        assertThat(replay(ExceptionHandlingStrategy.THROW).getReplayStatus()).isEqualTo("stopped");
        api.respond(STATUS_PATH, 200, "<player_status status=\"playing\"/>");
        assertThat(replay(ExceptionHandlingStrategy.THROW).getReplayStatus()).isEqualTo("playing");
        assertThat(sent()).containsExactly("GET /v1/replay/status?node_id=7", "GET /v1/replay/status?node_id=7");
    }

    @ParameterizedTest
    @EnumSource(ExceptionHandlingStrategy.class)
    void aStatusTheApiFailsIsNullUnderEitherStrategy(ExceptionHandlingStrategy strategy) {
        api.respond(STATUS_PATH, 500, BROKEN);

        assertThat(replay(strategy).getReplayStatus()).isNull();
    }

    /** A failed call is one warning line with its cause; the stack is at debug, and the answer is the same. */
    @Test
    void aFailedCallIsOneWarningLineWithItsCauseAndTheStackAtDebug() {
        api.respond(STATUS_PATH, 500, BROKEN);

        try (var log = LogCapture.of(Replay.class, Level.DEBUG)) {
            assertThat(replay(ExceptionHandlingStrategy.CATCH).getReplayStatus())
                    .isNull();
            assertThat(log.lines()).hasSize(2);
            assertThat(log.lines().getFirst())
                    .startsWith("WARN The replay status could not be fetched: ")
                    // the cause: the exception, with the message the API's answer gave
                    .contains("ApiException", "broken - replay")
                    .doesNotContain("\n");
            assertThat(log.lines().get(1)).isEqualTo("DEBUG The replay status could not be fetched");
        }
    }

    @Test
    void anEventTheFactoryFailsFailsTheListUnderThrow() {
        api.respond(LIST_PATH, 200, list("od:match:1", "od:match:2"));
        var failure = new IllegalStateException("cannot build od:match:2");
        built.fail(URN.parse("od:match:2"), failure);

        assertThatThrownBy(() -> replay(ExceptionHandlingStrategy.THROW).getReplayList())
                .isSameAs(failure);
    }

    @Test
    void anEventTheFactoryFailsMakesTheListNullUnderCatchNeverShort() {
        api.respond(LIST_PATH, 200, list("od:match:1", "od:match:2", "od:match:3"));
        built.fail(URN.parse("od:match:2"), new IllegalStateException("cannot build od:match:2"));

        assertThat(replay(ExceptionHandlingStrategy.CATCH).getReplayList()).isNull();
    }

    /** 0.0.x threw here under either strategy; under CATCH a getter answers null instead. */
    @Test
    void anIdThatIsNoUrnFailsTheListUnderThrow() {
        api.respond(LIST_PATH, 200, list("od:match:1", "not-an-urn"));

        assertThatThrownBy(() -> replay(ExceptionHandlingStrategy.THROW).getReplayList())
                .isInstanceOf(UnsupportedUrnFormatException.class)
                .hasMessageContaining("not-an-urn");
    }

    @Test
    void anIdThatIsNoUrnMakesTheListNullUnderCatch() {
        api.respond(LIST_PATH, 200, list("od:match:1", "not-an-urn"));

        assertThat(replay(ExceptionHandlingStrategy.CATCH).getReplayList()).isNull();
    }

    private Replay replay(ExceptionHandlingStrategy strategy) {
        return new Replay(client, built, strategy);
    }

    private List<String> sent() {
        return api.requests().stream().map(ReplayTest::line).toList();
    }

    private static String line(RecordedRequest r) {
        return r.method() + " " + r.path() + (r.query() == null ? "" : "?" + r.query());
    }

    private static String list(String... ids) {
        var body = new StringBuilder("<replay_set_content>");
        for (int i = 0; i < ids.length; i++) {
            body.append("<replay_event id=\"")
                    .append(ids[i])
                    .append("\" position=\"")
                    .append(i)
                    .append("\"/>");
        }
        return body.append("</replay_set_content>").toString();
    }

    /** A factory that records what it built, and fails on the id it is told to. */
    private static final class Built implements SportEventFactory {
        final List<SportEvent> events = new CopyOnWriteArrayList<>();
        private @Nullable URN failOn;
        private RuntimeException failure = new IllegalStateException("not set");

        void fail(URN id, RuntimeException failure) {
            this.failOn = id;
            this.failure = failure;
        }

        @Override
        public SportEvent build(URN id) {
            if (id.equals(failOn)) {
                throw failure;
            }
            var event = new Event(id);
            events.add(event);
            return event;
        }
    }

    /** A sport event with nothing but an id. */
    private record Event(@Nullable URN id) implements SportEvent {

        @Override
        public @Nullable URN getId() {
            return id;
        }

        @Deprecated
        @Override
        @SuppressWarnings("InlineMeSuggester") // an implementation of the interface, not an API to inline
        public @Nullable URN getRefId() {
            return null;
        }

        @Override
        public @Nullable String getName(Locale locale) {
            return null;
        }

        @Override
        public @Nullable URN getSportId() {
            return null;
        }

        @Override
        public @Nullable Date getScheduledTime() {
            return null;
        }

        @Override
        public @Nullable Date getScheduledEndTime() {
            return null;
        }

        @Override
        public @Nullable LiveOddsAvailability getLiveOddsAvailability() {
            return null;
        }
    }
}
