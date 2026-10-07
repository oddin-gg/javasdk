package com.oddin.oddsfeedsdk.internal.feed;

import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The recovery actor's one events sink, telling each of the feed's in turn: the events dispatcher,
 * which the client hears from, and whatever else keeps track of producers and sessions. Runs on the
 * actor's thread, so each is told and returns; one that throws is logged, and the next is told all
 * the same.
 */
final class RecoveryTee implements RecoveryEvents {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryTee.class);

    private final List<RecoveryEvents> told;

    /** @param told in the order they are told */
    RecoveryTee(List<? extends RecoveryEvents> told) {
        this.told = List.copyOf(told);
    }

    @Override
    public void producerStatus(ProducerStatusChange change) {
        tell("producerStatus", events -> events.producerStatus(change));
    }

    @Override
    public void producerCause(ProducerStatusChange change) {
        tell("producerCause", events -> events.producerCause(change));
    }

    @Override
    public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
        tell("eventRecoveryCompleted", events -> events.eventRecoveryCompleted(producerId, eventId, requestId));
    }

    @Override
    public void safetyNetReset(int session, long producerId, long ageMillis) {
        tell("safetyNetReset", events -> events.safetyNetReset(session, producerId, ageMillis));
    }

    @Override
    public void safetyNetRequestFailed(int session, long producerId, String reason) {
        tell("safetyNetRequestFailed", events -> events.safetyNetRequestFailed(session, producerId, reason));
    }

    @Override
    public void lagging(int session, boolean lagging) {
        tell("lagging", events -> events.lagging(session, lagging));
    }

    private void tell(String event, Consumer<RecoveryEvents> tell) {
        for (RecoveryEvents events : told) {
            try {
                tell.accept(events);
            } catch (RuntimeException e) {
                LOG.error("A recovery events listener threw on {}; the others are told", event, e);
            }
        }
    }
}
