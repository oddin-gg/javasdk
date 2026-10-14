package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The ledger accepts what the contract allows and fails what it does not. No broker. */
class LedgerTest {

    private static final String MATCH = "od:match:1";
    private static final String OTHER = "od:match:2";

    @Test
    void anUntouchedRunIsExactAndAPrefix() {
        var ledger = ledger(List.of(0L, 1L, 2L), List.of(0L, 1L, 2L));

        assertThatCode(ledger::assertExact).doesNotThrowAnyException();
        assertThatCode(ledger::assertPrefix).doesNotThrowAnyException();
        assertThat(ledger.deliveredCount()).isEqualTo(ledger.publishedCount());
    }

    @Test
    void aDroppedMessageFailsExactAndPrefix() {
        var ledger = ledger(List.of(0L, 1L, 2L, 3L), List.of(0L, 1L, 3L));

        assertThatThrownBy(ledger::assertExact)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(MATCH);
        assertThatThrownBy(ledger::assertPrefix)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(MATCH);
    }

    @Test
    void aDuplicatedMessageFailsEveryCheck() {
        var ledger = ledger(List.of(0L, 1L, 2L), List.of(0L, 1L, 1L, 2L));

        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
        assertThatThrownBy(ledger::assertPrefix).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 0L)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aReorderedMessageFailsEveryCheck() {
        var ledger = ledger(List.of(0L, 1L, 2L), List.of(0L, 2L, 1L));

        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
        assertThatThrownBy(ledger::assertPrefix).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 0L)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aDeliveryOfAnUnpublishedMessageFails() {
        var ledger = ledger(List.of(0L, 1L), List.of(0L, 1L, 9L));

        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 0L)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aMissingTailIsAPrefixButNotExact() {
        var ledger = ledger(List.of(0L, 1L, 2L, 3L), List.of(0L, 1L));

        assertThatCode(ledger::assertPrefix).doesNotThrowAnyException();
        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
    }

    @Test
    void aGapIsAllowedBeforeTheReconnectAndNotAfter() {
        var ledger = ledger(List.of(0L, 1L, 2L, 3L, 4L), List.of(0L, 3L, 4L));

        assertThatCode(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 3L)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 2L)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(MATCH);
    }

    @Test
    void everyMatchIsCheckedOnItsOwn() {
        var ledger = ledger(List.of(0L, 1L), List.of(0L, 1L));
        ledger.published(OTHER, 0);
        ledger.published(OTHER, 1);
        ledger.delivered(OTHER, 1);

        assertThatThrownBy(ledger::assertExact).hasMessageContaining(OTHER);
    }

    @Test
    void theSequenceRidesInTheTimestampAndTheMatchInTheEventId() {
        var message = new Ledger(1_000).message(OTHER, 5);

        assertThat(message).contains("timestamp=\"1005\"").contains("event_id=\"" + OTHER + "\"");
    }

    private static Ledger ledger(List<Long> published, List<Long> delivered) {
        var ledger = new Ledger(1_000);
        published.forEach(sequence -> ledger.published(MATCH, sequence));
        delivered.forEach(sequence -> ledger.delivered(MATCH, sequence));
        return ledger;
    }
}
