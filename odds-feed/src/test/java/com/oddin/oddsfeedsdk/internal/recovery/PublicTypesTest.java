package com.oddin.oddsfeedsdk.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import com.oddin.oddsfeedsdk.subscribe.ProducerStatusCause;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The recovery's own types as the client hears them: every cause and every state has its public
 * counterpart, one to one, and a status keeps every field.
 */
class PublicTypesTest {

    @Test
    void everyCauseHasAPublicCauseOfItsOwnAndEveryPublicCauseIsOne() {
        var mapped = EnumSet.noneOf(ProducerStatusCause.class);
        for (StatusCause cause : StatusCause.values()) {
            assertThat(mapped.add(cause.toPublic()))
                    .as("%s maps to a public cause no other cause does", cause)
                    .isTrue();
        }
        assertThat(mapped).as("every public cause").isEqualTo(EnumSet.allOf(ProducerStatusCause.class));
    }

    /** The public names say what the 0.0.x reasons say where they mean the same; the rest are as kept. */
    @Test
    void aPublicCauseIsNamedAsTheCauseOrAsTheReasonItShares() {
        for (StatusCause cause : StatusCause.values()) {
            String name = cause.toPublic().name();
            switch (cause) {
                case DELAY_STABILIZED -> assertThat(name).isEqualTo("PROCESSING_QUEUE_DELAY_STABILIZED");
                case PROCESSING_DELAY ->
                    assertThat(name).isEqualTo(cause.reason().name());
                default -> assertThat(name).as("%s", cause).isEqualTo(cause.name());
            }
        }
    }

    @Test
    void everyStateHasAPublicStateOfItsOwnAndEveryPublicStateIsOne() {
        List<String> mapped = Arrays.stream(EventRecoveryStatus.State.values())
                .map(state -> state.toPublic().name())
                .toList();
        assertThat(mapped)
                .as("each state as the public one of its name")
                .containsExactly(Arrays.stream(EventRecoveryStatus.State.values())
                        .map(Enum::name)
                        .toArray(String[]::new));
        assertThat(mapped)
                .containsExactlyInAnyOrder(
                        Arrays.stream(com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus.State.values())
                                .map(Enum::name)
                                .toArray(String[]::new));
    }

    @Test
    void aStatusKeepsEveryFieldAsTheClientReadsIt() {
        var event = URN.parse("od:match:7");
        var startedAt = Instant.ofEpochMilli(1_000);
        var endedAt = Instant.ofEpochMilli(2_000);
        var pending = EventRecoveryStatus.pending(42, 3, event, startedAt);
        assertThat(pending.toPublic())
                .isEqualTo(new com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus(
                        42,
                        3,
                        event,
                        com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus.State.PENDING,
                        startedAt,
                        null,
                        null));
        assertThat(pending.ended(EventRecoveryStatus.State.TIMED_OUT, endedAt, "no snapshot complete")
                        .toPublic())
                .isEqualTo(new com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus(
                        42,
                        3,
                        event,
                        com.oddin.oddsfeedsdk.api.entities.EventRecoveryStatus.State.TIMED_OUT,
                        startedAt,
                        endedAt,
                        "no snapshot complete"));
    }
}
