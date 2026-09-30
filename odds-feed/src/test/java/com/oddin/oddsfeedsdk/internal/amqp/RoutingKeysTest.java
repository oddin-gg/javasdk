package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The keys a session binds, as 0.0.x bound them. */
class RoutingKeysTest {

    @Test
    void anInterestBindsItsKeysTheSnapshotCompletionsAndTheAlives() {
        assertThat(RoutingKeys.forSession(MessageInterest.LIVE_ONLY, List.of(), null, true))
                .containsExactly("*.*.live.*.*.*.*.#", "-.-.-.snapshot_complete.-.-.-.-", "-.-.-.alive.#");
    }

    @Test
    void withANodeIdItBindsTheNodesOwnMessagesAndThoseForEveryNode() {
        assertThat(RoutingKeys.forSession(MessageInterest.PREMATCH_ONLY, List.of(), 7, true))
                .containsExactly(
                        "*.pre.*.*.*.*.*.7.#",
                        "*.pre.*.*.*.*.*.-.#",
                        "-.-.-.snapshot_complete.-.-.-.7",
                        "-.-.-.alive.#");
    }

    @Test
    void specifiedMatchesBindOneKeyPerEvent() {
        assertThat(RoutingKeys.forSession(
                        MessageInterest.SPECIFIED_MATCHES_ONLY,
                        List.of(URN.parse("od:match:1"), URN.parse("od:match:2")),
                        null,
                        true))
                .containsExactly(
                        "#.od:match.1.#", "#.od:match.2.#", "-.-.-.snapshot_complete.-.-.-.-", "-.-.-.alive.#");
    }

    @Test
    void anAliveSessionDoesNotBindTheAlivesTwice() {
        assertThat(RoutingKeys.forSession(MessageInterest.SYSTEM_ALIVE_ONLY, List.of(), null, true))
                .containsExactly("-.-.-.alive.#.#", "-.-.-.snapshot_complete.-.-.-.-");
    }

    @Test
    void theLowPrioritySessionNextToAHighPriorityOneTakesNoSnapshotCompletions() {
        Set<MessageInterest> both = Set.of(MessageInterest.HI_PRIORITY_ONLY, MessageInterest.LOW_PRIORITY_ONLY);
        assertThat(RoutingKeys.takesSnapshotComplete(MessageInterest.LOW_PRIORITY_ONLY, both))
                .isFalse();
        assertThat(RoutingKeys.takesSnapshotComplete(MessageInterest.HI_PRIORITY_ONLY, both))
                .isTrue();
        assertThat(RoutingKeys.takesSnapshotComplete(
                        MessageInterest.LOW_PRIORITY_ONLY, Set.of(MessageInterest.LOW_PRIORITY_ONLY)))
                .isTrue();
    }
}
