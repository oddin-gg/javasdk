package com.oddin.oddsfeedsdk.internal.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.OddsFeedSessionBuilder;
import com.oddin.oddsfeedsdk.ReplaySession;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedExtListener;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The session builder, as 0.0.x's: its messages, what it forgets after a build, and the registry it fills. */
class SessionBuilderTest {

    private static final URN FIRST = URN.parse("od:match:1");
    private static final URN SECOND = URN.parse("od:match:2");
    private static final URN THIRD = URN.parse("od:tournament:3");

    private final OddsFeedListener listener = Doubles.listener();
    private final OddsFeedExtListener extListener = Doubles.extListener();
    private final SessionRegistry registry = new SessionRegistry(extListener);
    private final OddsFeedSessionBuilder builder = registry.builder();

    @Test
    void aSessionIsRegisteredWithWhatItWasBuiltWith() {
        OddsFeedSession session = builder.setListener(listener)
                .setMessageInterest(MessageInterest.LIVE_ONLY)
                .build();

        assertThat(session).isNotInstanceOf(ReplaySession.class);
        var spec = only(registry.open());
        assertThat(spec.session()).isSameAs(session);
        assertThat(spec.id()).isEqualTo(1);
        assertThat(spec.interest()).isEqualTo(MessageInterest.LIVE_ONLY);
        assertThat(spec.events()).isEmpty();
        assertThat(spec.listener()).isSameAs(listener);
        assertThat(spec.extListener()).isSameAs(extListener);
        assertThat(spec.replay()).isFalse();
        assertThat(session).hasToString("session 1 (LIVE_ONLY)");
    }

    @Test
    void theRegistryNamesASessionByItsIdOnceTheFeedIsOpen() {
        OddsFeedSession first = builder.setListener(listener)
                .setMessageInterest(MessageInterest.HI_PRIORITY_ONLY)
                .build();
        OddsFeedSession second = builder.setListener(listener)
                .setMessageInterest(MessageInterest.LOW_PRIORITY_ONLY)
                .build();
        assertThat(registry.session(1)).as("before open()").isNull();

        registry.open();
        assertThat(registry.session(1)).isSameAs(first);
        assertThat(registry.session(2)).isSameAs(second);
        assertThat(registry.session(0)).as("no session 0").isNull();
        assertThat(registry.session(3)).as("one past the last").isNull();
    }

    @Test
    void aFeedWithoutAnExtendedListenerGivesItsSessionsNone() {
        var plain = new SessionRegistry(null);
        plain.builder()
                .setListener(listener)
                .setMessageInterest(MessageInterest.ALL)
                .build();

        assertThat(only(plain.open()).extListener()).isNull();
    }

    @Test
    void buildingWithoutAnInterestSaysSoBeforeItLooksAtTheListener() {
        assertThatThrownBy(builder::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message interest not specified");
        assertThatThrownBy(builder.setListener(listener)::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message interest not specified");
        assertThat(registry.isOpen()).isFalse();
    }

    @Test
    void buildingWithoutAListenerSaysSo() {
        assertThatThrownBy(builder.setMessageInterest(MessageInterest.ALL)::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Listener not specified");
        assertThatThrownBy(builder::buildReplay)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Listener not specified");
        assertThatThrownBy(registry::open).hasMessage("Feed created without sessions");
    }

    @Test
    void aBuildForgetsTheInterestAndTheEventsButKeepsTheListener() {
        builder.setListener(listener).setSpecificEventsOnly(Set.of(FIRST)).build();

        assertThatThrownBy(builder::build).hasMessage("Message interest not specified");
        builder.setSpecificEventOnly(SECOND).build();
        builder.setMessageInterest(MessageInterest.PREMATCH_ONLY).build();

        var specs = registry.open();
        assertThat(specs).extracting(SessionSpec::events).containsExactly(Set.of(FIRST), Set.of(SECOND), Set.of());
        assertThat(specs).extracting(SessionSpec::listener).containsOnly(listener);
    }

    @Test
    void specificEventsAccumulateInTheirOrderAndMakeTheInterestSpecifiedMatches() {
        builder.setListener(listener)
                .setMessageInterest(MessageInterest.LIVE_ONLY)
                .setSpecificEventsOnly(new LinkedHashSet<>(List.of(SECOND, FIRST)))
                .setSpecificEventOnly(THIRD)
                .setSpecificEventOnly(SECOND)
                .build();

        var spec = only(registry.open());
        assertThat(spec.interest()).isEqualTo(MessageInterest.SPECIFIED_MATCHES_ONLY);
        assertThat(spec.events()).containsExactly(SECOND, FIRST, THIRD);
    }

    @Test
    void anInterestSetAfterTheEventsWinsAndTheEventsAreKeptAsIn0x() {
        builder.setListener(listener)
                .setSpecificEventOnly(FIRST)
                .setMessageInterest(MessageInterest.LIVE_ONLY)
                .build();

        var spec = only(registry.open());
        assertThat(spec.interest()).isEqualTo(MessageInterest.LIVE_ONLY);
        assertThat(spec.events()).containsExactly(FIRST);
    }

    @Test
    void theEventsASessionWasBuiltWithAreItsOwn() {
        var events = new LinkedHashSet<>(List.of(FIRST));
        builder.setListener(listener).setSpecificEventsOnly(events).build();
        events.add(SECOND);

        var spec = only(registry.open());
        assertThat(spec.events()).containsExactly(FIRST);
        assertThatThrownBy(() -> spec.events().add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aReplaySessionIsBothKindsOfSessionTakesAllAndForgetsNothing() {
        builder.setListener(listener)
                .setMessageInterest(MessageInterest.LIVE_ONLY)
                .setSpecificEventOnly(FIRST);

        ReplaySession replay = builder.buildReplay();
        OddsFeedSession next = builder.build();

        assertThat(replay).isInstanceOf(OddsFeedSession.class);
        var specs = registry.open();
        assertThat(specs.getFirst().session()).isSameAs(replay);
        assertThat(specs.getFirst().interest()).isEqualTo(MessageInterest.ALL);
        assertThat(specs.getFirst().events()).isEmpty();
        assertThat(specs.getFirst().replay()).isTrue();
        assertThat(replay).hasToString("replay session 1 (ALL)");
        // the interest and the event a replay build left alone are the next session's
        assertThat(specs.get(1).session()).isSameAs(next);
        assertThat(specs.get(1).interest()).isEqualTo(MessageInterest.SPECIFIED_MATCHES_ONLY);
        assertThat(specs.get(1).events()).containsExactly(FIRST);
        assertThat(specs.get(1).replay()).isFalse();
    }

    @Test
    void theBuildersOfAFeedNumberItsSessionsInTheOrderTheyWereBuilt() {
        var other = registry.builder().setListener(listener);
        builder.setListener(listener);

        var sessions = new ArrayList<OddsFeedSession>();
        sessions.add(
                builder.setMessageInterest(MessageInterest.HI_PRIORITY_ONLY).build());
        sessions.add(other.setMessageInterest(MessageInterest.LOW_PRIORITY_ONLY).build());
        sessions.add(
                builder.setMessageInterest(MessageInterest.SYSTEM_ALIVE_ONLY).build());

        var specs = registry.open();
        assertThat(specs).extracting(SessionSpec::id).containsExactly(1, 2, 3);
        assertThat(specs).extracting(spec -> (OddsFeedSession) spec.session()).containsExactlyElementsOf(sessions);
        assertThat(specs.get(1).session().id()).isEqualTo(2);
    }

    @Test
    void onceTheFeedIsOpenNoSessionCanBeBuilt() {
        builder.setListener(listener).setMessageInterest(MessageInterest.ALL).build();
        var taken = registry.open();

        builder.setMessageInterest(MessageInterest.LIVE_ONLY);
        assertThatThrownBy(builder::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("the feed is already open; build sessions before open()");
        assertThatThrownBy(builder::buildReplay)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("the feed is already open; build sessions before open()");
        assertThatThrownBy(
                        registry.builder().setListener(listener).setMessageInterest(MessageInterest.LIVE_ONLY)::build)
                .hasMessage("the feed is already open; build sessions before open()");

        assertThat(registry.isOpen()).isTrue();
        assertThat(taken).hasSize(1);
    }

    @Test
    void theArgumentsABuildChecksStillComeFirstOnceTheFeedIsOpen() {
        builder.setListener(listener).setMessageInterest(MessageInterest.ALL).build();
        registry.open();

        assertThatThrownBy(builder::build).hasMessage("Message interest not specified");
        var withoutAListener = registry.builder().setMessageInterest(MessageInterest.LIVE_ONLY);
        assertThatThrownBy(withoutAListener::build)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Listener not specified");
        assertThatThrownBy(withoutAListener::buildReplay)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Listener not specified");
    }

    @Test
    void aFeedWithoutSessionsStaysUnopenedSoASessionCanStillBeBuilt() {
        assertThatThrownBy(registry::open)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Feed created without sessions");
        assertThat(registry.isOpen()).isFalse();

        builder.setListener(listener).setMessageInterest(MessageInterest.ALL).build();
        assertThat(registry.open()).hasSize(1);
    }

    @Test
    void theSessionsAreTakenOnceAndAsTheyWere() {
        builder.setListener(listener).setMessageInterest(MessageInterest.ALL).build();
        var taken = registry.open();

        assertThatThrownBy(registry::open).isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> taken.add(taken.getFirst())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullArgumentsAreRefusedAndChangeNothing() {
        assertThatThrownBy(() -> builder.setListener(nullValue())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.setMessageInterest(nullValue())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.setSpecificEventOnly(nullValue())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.setSpecificEventsOnly(nullValue())).isInstanceOf(NullPointerException.class);
        var withNull = new LinkedHashSet<URN>(Arrays.asList(FIRST, null));
        assertThatThrownBy(() -> builder.setSpecificEventsOnly(withNull)).isInstanceOf(NullPointerException.class);

        builder.setListener(listener);
        assertThatThrownBy(builder::build).hasMessage("Message interest not specified");
        builder.setSpecificEventOnly(SECOND).build();
        assertThat(only(registry.open()).events()).containsExactly(SECOND);
    }

    private static SessionSpec only(List<SessionSpec> specs) {
        assertThat(specs).hasSize(1);
        return specs.getFirst();
    }

    /** A null where the API says there is none, as a careless caller passes it. */
    @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
    private static <T> T nullValue() {
        return null;
    }
}
