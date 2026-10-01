package com.oddin.oddsfeedsdk.internal.recovery;

/**
 * What the alive dispatcher tells the recovery actor of the alives on the SDK's own alive channel:
 * the facts producer liveness is made of. The method hands the fact over and returns at once.
 */
public interface AliveFacts {

    /**
     * An alive arrived.
     *
     * @param generatedAt the alive's timestamp, epoch millis by the producer's clock
     * @param receivedAt when the SDK received it, epoch millis by its own clock
     */
    void alive(long producerId, long generatedAt, long receivedAt, boolean subscribed);
}
