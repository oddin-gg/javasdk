package com.oddin.oddsfeedsdk.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    void nothingDeliveredIsAPrefixOfWhatWasPublished() {
        var ledger = ledger(List.of(0L, 1L), List.of());

        assertThatCode(ledger::assertPrefix).doesNotThrowAnyException();
        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
    }

    @Test
    void aMessageSentWithAnErrorMayArriveButIsNotRequiredTo() {
        var ledger = ledger(List.of(0L, 2L), List.of(0L, 1L, 2L));
        ledger.sentWithError(MATCH, 1);
        ledger.sentWithError(MATCH, 3);

        assertThatCode(() -> ledger.assertGapsOnlyAfterReconnect(Map.of(MATCH, 0L)))
                .doesNotThrowAnyException();
        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
    }

    @Test
    void aMessageSentWithAnErrorThatArrivedKeepsThePrefix() {
        var ledger = ledger(List.of(0L, 1L, 3L), List.of(0L, 1L, 2L, 3L));
        ledger.sentWithError(MATCH, 2);

        assertThatCode(ledger::assertPrefix).doesNotThrowAnyException();
    }

    @Test
    void aMessageSentWithAnErrorThatDidNotArriveKeepsThePrefix() {
        var ledger = ledger(List.of(0L, 1L, 3L), List.of(0L, 1L, 3L));
        ledger.sentWithError(MATCH, 2);
        var trailing = ledger(List.of(0L, 1L), List.of(0L, 1L));
        trailing.sentWithError(MATCH, 2);

        assertThatCode(ledger::assertPrefix).doesNotThrowAnyException();
        assertThatCode(trailing::assertPrefix).doesNotThrowAnyException();
    }

    @Test
    void aMissingPublishedMessageBeforeAnArrivedOneIsAGapEvenWithAnUncertainOne() {
        var ledger = ledger(List.of(0L, 1L, 3L), List.of(0L, 3L));
        ledger.sentWithError(MATCH, 2);

        assertThatThrownBy(ledger::assertPrefix)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(MATCH);
    }

    @Test
    void anArrivalNeitherPublishedNorSentWithAnErrorFailsThePrefix() {
        var ledger = ledger(List.of(0L, 1L), List.of(0L, 1L, 2L));
        ledger.sentWithError(MATCH, 5);

        assertThatThrownBy(ledger::assertPrefix).isInstanceOf(AssertionError.class);
    }

    @Test
    void whatWasSentWithAnErrorIsCountedSinceAMark() {
        var ledger = ledger(List.of(0L, 1L), List.of(0L));
        ledger.sentWithError(MATCH, 2);
        ledger.sentWithError(MATCH, 3);
        ledger.sentWithError(OTHER, 0);

        assertThat(ledger.uncertainSince(Map.of(MATCH, 3L))).isEqualTo(2);
        assertThat(ledger.uncertainSince(Map.of(MATCH, 0L))).isEqualTo(3);
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
    void whatCameSinceAMarkIsCountedPerMatch() {
        var ledger = ledger(List.of(0L, 1L, 2L, 3L), List.of(0L, 3L));
        ledger.published(OTHER, 0);
        var mark = Map.of(MATCH, 2L);

        assertThat(ledger.publishedSince(mark)).isEqualTo(3);
        assertThat(ledger.deliveredSince(mark)).isEqualTo(1);
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

    @Test
    void aLedgerThatPublishedNothingFailsEveryCheck() {
        var ledger = new Ledger(1_000);

        assertThatThrownBy(ledger::assertExact).isInstanceOf(AssertionError.class);
        assertThatThrownBy(ledger::assertPrefix).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ledger.assertGapsOnlyAfterReconnect(Map.of())).isInstanceOf(AssertionError.class);
    }

    @Test
    void theTrafficIsLiveNotSnapshotMessages() {
        assertThat(new Ledger(1_000).message(MATCH, 0)).doesNotContain("request_id");
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPublishStillInTheBrokerIsAfterTheMark() throws Exception {
        var ledger = new Ledger(1_000);
        ledger.publish(_ -> true, MATCH);
        ledger.publish(_ -> true, MATCH);
        var inBroker = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publisher = new Thread(() -> ledger.publish(
                _ -> {
                    inBroker.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                },
                MATCH));
        publisher.start();
        try {
            assertThat(inBroker.await(30, TimeUnit.SECONDS)).isTrue();

            var mark = ledger.mark();

            // sequence 2 is in the broker: it may be routed after the reconnect, so it is after the mark
            assertThat(mark).containsEntry(MATCH, 2L);
        } finally {
            release.countDown();
            publisher.join(30_000);
        }
        assertThat(ledger.publishedSince(Map.of(MATCH, 2L))).isEqualTo(1);
        assertThat(ledger.mark()).containsEntry(MATCH, 3L);
    }

    @Test
    void aPublishAdvancesTheMarkWhateverTheBrokerAnswers() {
        var ledger = new Ledger(1_000);

        ledger.publish(_ -> true, MATCH);
        ledger.publish(_ -> false, MATCH);
        assertThatThrownBy(() -> ledger.publish(
                        _ -> {
                            throw new IllegalStateException("channel closed");
                        },
                        MATCH))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.mark()).containsEntry(MATCH, 3L);
        assertThat(ledger.publishedCount()).isEqualTo(1);
    }

    private static Ledger ledger(List<Long> published, List<Long> delivered) {
        var ledger = new Ledger(1_000);
        published.forEach(sequence -> ledger.published(MATCH, sequence));
        delivered.forEach(sequence -> ledger.delivered(MATCH, sequence));
        return ledger;
    }
}
