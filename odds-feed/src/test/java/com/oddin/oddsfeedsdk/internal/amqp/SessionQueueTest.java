package com.oddin.oddsfeedsdk.internal.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What the session queue takes, turns away and refuses. */
class SessionQueueTest {

    @Test
    void aLateDeliveryOfAReplacedChannelIsTurnedAwayAndCounted() {
        var queue = new SessionQueue(10);
        assertThat(queue.offer(delivery(0))).isEqualTo(SessionQueue.Offer.QUEUED);
        queue.removeEpochsBefore(1);
        assertThat(queue.size()).as("the old channel's deliveries are out").isZero();

        assertThat(queue.offer(delivery(0))).isEqualTo(SessionQueue.Offer.STALE);
        assertThat(queue.size()).isZero();
        assertThat(queue.stale()).isEqualTo(1);
        assertThat(queue.overflowed()).isZero();
        assertThat(queue.offer(delivery(1))).isEqualTo(SessionQueue.Offer.QUEUED);
    }

    @Test
    void theDeliveriesAReplacementTakesOutAndTurnsAwayAreEpochDiscards() {
        var queue = new SessionQueue(10);
        queue.offer(delivery(0));
        queue.offer(delivery(0));
        queue.offer(delivery(1));
        queue.removeEpochsBefore(1);
        assertThat(queue.epochDiscards())
                .as("the two of the old channel taken out")
                .isEqualTo(2);
        assertThat(queue.size()).as("the new channel's stays").isEqualTo(1);

        queue.offer(delivery(0));
        assertThat(queue.epochDiscards()).as("and the late one turned away").isEqualTo(3);
        queue.offer(delivery(1));
        queue.removeEpochsBefore(1);
        assertThat(queue.epochDiscards()).as("the current channel's are none").isEqualTo(3);
    }

    @Test
    void anOlderEpochDoesNotLetOlderDeliveriesBackIn() {
        var queue = new SessionQueue(10);
        queue.removeEpochsBefore(3);
        queue.removeEpochsBefore(1);
        assertThat(queue.offer(delivery(2))).isEqualTo(SessionQueue.Offer.STALE);
        assertThat(queue.offer(delivery(3))).isEqualTo(SessionQueue.Offer.QUEUED);
    }

    @Test
    void aFullQueueRefusesWithoutWaiting() throws InterruptedException {
        var queue = new SessionQueue(1);
        assertThat(queue.offer(delivery(0))).isEqualTo(SessionQueue.Offer.QUEUED);
        assertThat(queue.offer(delivery(0))).isEqualTo(SessionQueue.Offer.FULL);
        assertThat(queue.overflowed()).isEqualTo(1);
        assertThat(queue.stale()).isZero();
        assertThat(queue.poll(Duration.ZERO)).isNotNull();
        assertThat(queue.poll(Duration.ZERO)).isNull();
    }

    private static RawDelivery delivery(long epoch) {
        return new RawDelivery(new byte[0], 0, "hi.-.live.alive.-.-.-.-", 1, epoch, Instant.EPOCH, null);
    }
}
