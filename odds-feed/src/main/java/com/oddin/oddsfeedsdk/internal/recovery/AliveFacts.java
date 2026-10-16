package com.oddin.oddsfeedsdk.internal.recovery;

import com.oddin.oddsfeedsdk.internal.clock.Stamp;

/**
 * What the alive dispatcher tells the recovery actor of the alives on the SDK's own alive channel:
 * the facts producer liveness is made of. The method hands the fact over and returns at once.
 */
public interface AliveFacts {

    /**
     * An alive arrived.
     *
     * @param generatedAt the alive's timestamp, epoch millis by the producer's clock
     * @param markAt what the safe point is taken from: {@code generatedAt}, or the producer's now at
     *     receipt, by the offset known before this alive, when it is stamped ahead of it
     * @param received when the SDK received it: both readings of its clock, taken where the broker's
     *     delivery arrived
     */
    void alive(long producerId, long generatedAt, long markAt, Stamp received, boolean subscribed);

    /** An alive whose stamp is not ahead of the producer's now: its own stamp is its mark. */
    default void alive(long producerId, long generatedAt, Stamp received, boolean subscribed) {
        alive(producerId, generatedAt, generatedAt, received, subscribed);
    }
}
