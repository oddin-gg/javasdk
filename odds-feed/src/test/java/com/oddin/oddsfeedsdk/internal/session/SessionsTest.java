package com.oddin.oddsfeedsdk.internal.session;

import static com.oddin.oddsfeedsdk.mq.MessageInterest.ALL;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.HI_PRIORITY_ONLY;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.LIVE_ONLY;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.LOW_PRIORITY_ONLY;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.PREMATCH_ONLY;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.SPECIFIED_MATCHES_ONLY;
import static com.oddin.oddsfeedsdk.mq.MessageInterest.SYSTEM_ALIVE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.exceptions.UnsupportedMessageInterestCombination;
import com.oddin.oddsfeedsdk.internal.recovery.SessionInfo;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The plan {@code open()} follows, as 0.0.x's {@code open()} worked it out. */
class SessionsTest {

    private static final String DUPLICATE = "found duplicate message interests";
    private static final String ALL_ALONE = "all messages can be used only for single session configuration";
    private static final String PRIORITY = "cannot combine priority messages with other types";

    private static final URN MATCH = URN.parse("od:match:198314");
    private static final URN TOURNAMENT = URN.parse("od:tournament:7");

    private static final Map<Long, Producer> PRODUCERS = Map.of(
            1L, Doubles.producer(ProducerScope.PREMATCH),
            2L, Doubles.producer(ProducerScope.LIVE),
            3L, Doubles.producer(ProducerScope.LIVE, ProducerScope.PREMATCH));

    @Test
    void oneSessionMayHaveAnyInterest() {
        for (MessageInterest interest : MessageInterest.values()) {
            assertThatCode(() -> Sessions.validate(List.of(interest)))
                    .as("%s", interest)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void theCombinationsClientsUseAreAllowed() {
        for (List<MessageInterest> interests : List.of(
                List.of(HI_PRIORITY_ONLY, LOW_PRIORITY_ONLY),
                List.of(LIVE_ONLY, PREMATCH_ONLY),
                List.of(LIVE_ONLY, SYSTEM_ALIVE_ONLY),
                List.of(PREMATCH_ONLY, LIVE_ONLY, SPECIFIED_MATCHES_ONLY, SYSTEM_ALIVE_ONLY),
                List.of(HI_PRIORITY_ONLY, LOW_PRIORITY_ONLY, SPECIFIED_MATCHES_ONLY, SYSTEM_ALIVE_ONLY),
                List.of(SPECIFIED_MATCHES_ONLY, SYSTEM_ALIVE_ONLY))) {
            assertThatCode(() -> Sessions.validate(interests))
                    .as("%s", interests)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void twoSessionsOfOneInterestAreRefusedBeforeAnythingElse() {
        for (MessageInterest interest : MessageInterest.values()) {
            refused(List.of(interest, interest), DUPLICATE);
        }
        refused(List.of(LIVE_ONLY, HI_PRIORITY_ONLY, LIVE_ONLY), DUPLICATE);
        refused(List.of(ALL, LIVE_ONLY, LIVE_ONLY), DUPLICATE);
    }

    @Test
    void allIsRefusedNextToAnyOtherSession() {
        for (MessageInterest interest : MessageInterest.values()) {
            if (interest != ALL) {
                refused(List.of(ALL, interest), ALL_ALONE);
                refused(List.of(interest, ALL), ALL_ALONE);
            }
        }
        refused(List.of(ALL, HI_PRIORITY_ONLY, LIVE_ONLY), ALL_ALONE);
    }

    @Test
    void aPriorityInterestIsRefusedNextToALiveOrAPrematchOne() {
        for (MessageInterest priority : List.of(HI_PRIORITY_ONLY, LOW_PRIORITY_ONLY)) {
            for (MessageInterest scoped : List.of(LIVE_ONLY, PREMATCH_ONLY)) {
                refused(List.of(priority, scoped), PRIORITY);
                refused(List.of(scoped, SYSTEM_ALIVE_ONLY, priority), PRIORITY);
            }
        }
        refused(List.of(HI_PRIORITY_ONLY, LOW_PRIORITY_ONLY, LIVE_ONLY, PREMATCH_ONLY), PRIORITY);
    }

    /** Every set of distinct interests, against the three rules read as one. */
    @Test
    void everyCombinationOfDistinctInterestsFollowsTheRules() {
        var values = MessageInterest.values();
        for (int mask = 1; mask < 1 << values.length; mask++) {
            var interests = new ArrayList<MessageInterest>();
            for (int bit = 0; bit < values.length; bit++) {
                if ((mask & 1 << bit) != 0) {
                    interests.add(values[bit]);
                }
            }
            var several = interests.size() > 1;
            var priority = interests.contains(HI_PRIORITY_ONLY) || interests.contains(LOW_PRIORITY_ONLY);
            var scoped = interests.contains(LIVE_ONLY) || interests.contains(PREMATCH_ONLY);
            if (several && interests.contains(ALL)) {
                refused(interests, ALL_ALONE);
            } else if (several && priority && scoped) {
                refused(interests, PRIORITY);
            } else {
                assertThatCode(() -> Sessions.validate(interests))
                        .as("%s", interests)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void aPlanOfSessionsThatDoNotCombineIsRefused() {
        var sessions = List.of(spec(1, LIVE_ONLY), replay(2));

        assertThatThrownBy(() -> Sessions.plan(sessions, PRODUCERS, null))
                .isExactlyInstanceOf(UnsupportedMessageInterestCombination.class)
                .hasMessage(ALL_ALONE);
    }

    @Test
    void aPlanNeedsSessions() {
        assertThatThrownBy(() -> Sessions.plan(List.of(), PRODUCERS, null))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachSessionIsBoundWithItsInterestsKeysTheCompletionsOfItsNodeAndTheAlives() {
        var plan = Sessions.plan(List.of(spec(1, LIVE_ONLY), spec(2, PREMATCH_ONLY)), PRODUCERS, 4);

        assertThat(plan.sessions().get(0).routingKeys())
                .containsExactlyInAnyOrder(
                        "*.*.live.*.*.*.*.4.#",
                        "*.*.live.*.*.*.*.-.#",
                        "-.-.-.snapshot_complete.-.-.-.4",
                        "-.-.-.alive.#");
        assertThat(plan.sessions().get(1).routingKeys())
                .containsExactlyInAnyOrder(
                        "*.pre.*.*.*.*.*.4.#",
                        "*.pre.*.*.*.*.*.-.#",
                        "-.-.-.snapshot_complete.-.-.-.4",
                        "-.-.-.alive.#");
        assertThat(plan.sessions()).allMatch(Sessions.Planned::takesSnapshotComplete);
    }

    @Test
    void theLowPrioritySessionNextToAHighPriorityOneTakesNoCompletions() {
        var plan = Sessions.plan(List.of(spec(1, LOW_PRIORITY_ONLY), spec(2, HI_PRIORITY_ONLY)), PRODUCERS, null);

        var low = plan.sessions().get(0);
        assertThat(low.takesSnapshotComplete()).isFalse();
        assertThat(low.routingKeys()).containsExactlyInAnyOrder("lo.*.*.*.*.*.*.#", "-.-.-.alive.#");
        assertThat(low.info()).isEqualTo(new SessionInfo(1, LOW_PRIORITY_ONLY, false));
        var high = plan.sessions().get(1);
        assertThat(high.takesSnapshotComplete()).isTrue();
        assertThat(high.routingKeys())
                .containsExactlyInAnyOrder("hi.*.*.*.*.*.*.#", "-.-.-.snapshot_complete.-.-.-.-", "-.-.-.alive.#");
        assertThat(high.info()).isEqualTo(new SessionInfo(2, HI_PRIORITY_ONLY, true));

        var alone = Sessions.plan(List.of(spec(1, LOW_PRIORITY_ONLY)), PRODUCERS, null);
        assertThat(alone.sessions().getFirst().takesSnapshotComplete()).isTrue();
    }

    @Test
    void aSpecifiedMatchesSessionIsBoundToEachOfItsEvents() {
        var plan = Sessions.plan(List.of(spec(1, SPECIFIED_MATCHES_ONLY, MATCH, TOURNAMENT)), PRODUCERS, null);

        var session = plan.sessions().getFirst();
        assertThat(session.routingKeys())
                .containsExactlyInAnyOrder(
                        "#.od:match.198314.#",
                        "#.od:tournament.7.#",
                        "-.-.-.snapshot_complete.-.-.-.-",
                        "-.-.-.alive.#");
        assertThat(session.takesSnapshotComplete()).isTrue();
    }

    @Test
    void aSpecifiedMatchesSessionWithoutEventsIsBoundToTheAlivesOnly() {
        var plan = Sessions.plan(List.of(spec(1, SPECIFIED_MATCHES_ONLY), spec(2, LIVE_ONLY)), PRODUCERS, 4);

        var session = plan.sessions().getFirst();
        assertThat(session.routingKeys()).containsExactly("-.-.-.alive.#");
        assertThat(session.takesSnapshotComplete()).isFalse();
    }

    @Test
    void anotherInterestIgnoresTheEventsItWasGiven() {
        var plan = Sessions.plan(List.of(spec(1, LIVE_ONLY, MATCH)), PRODUCERS, null);

        assertThat(plan.sessions().getFirst().routingKeys())
                .containsExactlyInAnyOrder("*.*.live.*.*.*.*.#", "-.-.-.snapshot_complete.-.-.-.-", "-.-.-.alive.#");
    }

    /** Against a transcription of 0.0.x's {@code generateKeys}, for every combination the validation lets through. */
    @Test
    void everyAllowedCombinationIsBoundAs0xBoundIt() {
        var values = MessageInterest.values();
        var checked = 0;
        for (int mask = 1; mask < 1 << values.length; mask++) {
            var sessions = new ArrayList<SessionSpec>();
            for (int bit = 0; bit < values.length; bit++) {
                if ((mask & 1 << bit) != 0) {
                    sessions.add(spec(sessions.size() + 1, values[bit], MATCH, TOURNAMENT));
                }
            }
            if (!allowed(sessions)) {
                continue;
            }
            for (Integer nodeId : Arrays.asList(null, 9)) {
                for (boolean withEvents : List.of(true, false)) {
                    var planned = withEvents ? sessions : withoutEvents(sessions);
                    var plan = Sessions.plan(planned, PRODUCERS, nodeId);
                    for (int i = 0; i < planned.size(); i++) {
                        var session = plan.sessions().get(i);
                        assertThat(session.spec()).isSameAs(planned.get(i));
                        assertThat(session.routingKeys())
                                .as("%s of %s, node %s", planned.get(i).interest(), interests(planned), nodeId)
                                .containsExactlyInAnyOrderElementsOf(legacyKeys(planned, planned.get(i), nodeId))
                                .doesNotHaveDuplicates();
                        assertThat(session.takesSnapshotComplete())
                                .as("%s of %s takes completions", planned.get(i).interest(), interests(planned))
                                .isEqualTo(session.routingKeys().stream().anyMatch(key -> key.contains("snapshot")));
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).as("combinations checked").isGreaterThan(100);
    }

    @Test
    void theProducersNoSessionAsksForAreDisabled() {
        assertThat(disabled(LIVE_ONLY)).containsExactly(1L);
        assertThat(disabled(PREMATCH_ONLY)).containsExactly(2L);
        assertThat(disabled(LIVE_ONLY, PREMATCH_ONLY)).isEmpty();
        assertThat(disabled(LIVE_ONLY, SYSTEM_ALIVE_ONLY)).isEmpty();
        assertThat(disabled(PREMATCH_ONLY, SPECIFIED_MATCHES_ONLY)).isEmpty();
        for (MessageInterest interest : List.of(ALL, HI_PRIORITY_ONLY, LOW_PRIORITY_ONLY, SPECIFIED_MATCHES_ONLY)) {
            assertThat(disabled(interest)).as("%s", interest).isEmpty();
        }
        assertThat(Sessions.plan(List.of(replay(1)), PRODUCERS, null).disabledProducers())
                .isEmpty();
        assertThat(Sessions.plan(List.of(spec(1, LIVE_ONLY)), Map.of(), null).disabledProducers())
                .isEmpty();
    }

    @Test
    void theDisabledProducersAreInIdOrder() {
        Map<Long, Producer> prematch = Map.of(
                40L, Doubles.producer(ProducerScope.PREMATCH),
                3L, Doubles.producer(ProducerScope.PREMATCH),
                17L, Doubles.producer(ProducerScope.PREMATCH),
                5L, Doubles.producer(ProducerScope.LIVE));

        assertThat(Sessions.plan(List.of(spec(1, LIVE_ONLY)), prematch, null).disabledProducers())
                .containsExactly(3L, 17L, 40L);
    }

    @Test
    void aReplaySessionAloneMakesAReplayFeed() {
        var plan = Sessions.plan(List.of(replay(1)), PRODUCERS, 3);

        assertThat(plan.replay()).isTrue();
        assertThat(plan.sessions().getFirst().routingKeys())
                .containsExactlyInAnyOrder(
                        "*.*.*.*.*.*.*.3.#", "*.*.*.*.*.*.*.-.#", "-.-.-.snapshot_complete.-.-.-.3", "-.-.-.alive.#");
        assertThat(Sessions.plan(List.of(spec(1, ALL)), PRODUCERS, null).replay())
                .isFalse();
        assertThat(Sessions.plan(List.of(spec(1, LIVE_ONLY), spec(2, PREMATCH_ONLY)), PRODUCERS, null)
                        .replay())
                .isFalse();
    }

    private static void refused(List<MessageInterest> interests, String message) {
        assertThatThrownBy(() -> Sessions.validate(interests))
                .as("%s", interests)
                .isExactlyInstanceOf(UnsupportedMessageInterestCombination.class)
                .hasMessage(message)
                .hasNoCause();
    }

    private static boolean allowed(List<SessionSpec> sessions) {
        try {
            Sessions.validate(interests(sessions));
            return true;
        } catch (UnsupportedMessageInterestCombination e) {
            return false;
        }
    }

    private static Set<Long> disabled(MessageInterest... interests) {
        var sessions = new ArrayList<SessionSpec>();
        for (MessageInterest interest : interests) {
            sessions.add(spec(sessions.size() + 1, interest, MATCH));
        }
        return Sessions.plan(sessions, PRODUCERS, null).disabledProducers();
    }

    private static List<MessageInterest> interests(List<SessionSpec> sessions) {
        return sessions.stream().map(SessionSpec::interest).toList();
    }

    private static List<SessionSpec> withoutEvents(List<SessionSpec> sessions) {
        return sessions.stream()
                .map(session -> spec(session.id(), session.interest()))
                .toList();
    }

    /** 0.0.x's {@code OddsFeed.generateKeys}, line by line, for one of the feed's sessions. */
    private static List<String> legacyKeys(List<SessionSpec> all, SessionSpec it, @Nullable Integer nodeId) {
        var bothLowAndHigh = all.stream()
                        .filter(session ->
                                session.interest() == LOW_PRIORITY_ONLY || session.interest() == HI_PRIORITY_ONLY)
                        .count()
                == 2;
        var snapshotRoutingKey = String.format("-.-.-.snapshot_complete.-.-.-.%s", nodeId == null ? "-" : nodeId);
        var sessionRoutingKeys = new LinkedHashSet<String>();
        var basicRoutingKeys = it.interest() == SPECIFIED_MATCHES_ONLY
                ? it.events().stream()
                        .map(urn -> "#." + urn.getPrefix() + ":" + urn.getType() + "." + urn.getId())
                        .toList()
                : it.interest().getRoutingKeys();
        for (String key : basicRoutingKeys) {
            String basicRoutingKey;
            if (nodeId != null) {
                sessionRoutingKeys.add(key + "." + nodeId + ".#");
                basicRoutingKey = key + ".-.#";
            } else {
                basicRoutingKey = key + ".#";
            }
            if (bothLowAndHigh && it.interest() == LOW_PRIORITY_ONLY) {
                sessionRoutingKeys.add(basicRoutingKey);
            } else {
                sessionRoutingKeys.add(snapshotRoutingKey);
                sessionRoutingKeys.add(basicRoutingKey);
            }
        }
        if (it.interest() != SYSTEM_ALIVE_ONLY) {
            sessionRoutingKeys.add(SYSTEM_ALIVE_ONLY.getRoutingKeys().getFirst());
        }
        return List.copyOf(sessionRoutingKeys);
    }

    private static SessionSpec spec(int id, MessageInterest interest, URN... events) {
        return new SessionSpec(
                id,
                new FeedSession(id, interest),
                interest,
                new LinkedHashSet<>(List.of(events)),
                Doubles.listener(),
                null);
    }

    private static SessionSpec replay(int id) {
        return new SessionSpec(id, new ReplayFeedSession(id), ALL, Set.of(), Doubles.listener(), null);
    }
}
