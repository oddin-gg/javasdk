package com.oddin.oddsfeedsdk.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import com.oddin.oddsfeedsdk.internal.recovery.ProducerStatusChange;
import com.oddin.oddsfeedsdk.internal.recovery.RecoveryEvents;
import com.oddin.oddsfeedsdk.internal.recovery.SessionFacts;
import com.oddin.oddsfeedsdk.internal.recovery.StatusCause;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The connection's and the recovery actor's events told to each of the feed's listeners in turn, and
 * a session's channel events bound late to its facts.
 */
class ConnectionTeeTest {

    private final List<String> told = new ArrayList<>();

    @Test
    void eachListenerIsToldEveryEventInTurn() {
        var tee = new ConnectionTee(List.of(recording("actor"), recording("events")));

        tee.connecting();
        tee.up();
        tee.down("lost");
        tee.recovering(2, 500, "refused");
        tee.fatal("gone", null);

        assertThat(told)
                .containsExactly(
                        "actor connecting",
                        "events connecting",
                        "actor up",
                        "events up",
                        "actor down lost",
                        "events down lost",
                        "actor recovering 2 500 refused",
                        "events recovering 2 500 refused",
                        "actor fatal gone",
                        "events fatal gone");
    }

    @Test
    void aListenerThatThrowsKeepsNoOtherFromHearing() {
        var throwing = new ConnectionEvents() {
            @Override
            public void up() {
                throw new IllegalStateException("broken");
            }
        };
        var tee = new ConnectionTee(List.of(throwing, recording("events")));

        tee.up();

        assertThat(told).containsExactly("events up");
    }

    @Test
    void aSessionsChannelEventsTellNothingUntilBoundThenWhatTheyAreBoundTo() {
        var late = new LateChannelEvents();
        late.lost();
        late.reopened();
        assertThat(told).isEmpty();

        late.bind(new SessionFacts() {
            @Override
            public void processed(long producerId, long generatedAt, long takenAt, long requestId) {
                told.add("processed");
            }

            @Override
            public void alive(long producerId, long generatedAt, long takenAt, boolean subscribed) {
                told.add("alive");
            }

            @Override
            public void snapshotComplete(long producerId, long requestId) {
                told.add("snapshotComplete");
            }

            @Override
            public void channelLost() {
                told.add("lost");
            }

            @Override
            public void channelReopened() {
                told.add("reopened");
            }

            @Override
            public void closed() {
                told.add("closed");
            }
        });
        late.lost();
        late.reopened();

        assertThat(told).containsExactly("lost", "reopened");
    }

    @Test
    void theRecoveryActorsEventsAreToldToEachInTurnAndOneThatThrowsKeepsNoOtherFromHearing() {
        var throwing = new RecoveryEvents() {
            @Override
            public void lagging(int session, boolean lagging) {
                throw new IllegalStateException("broken");
            }
        };
        var change = new ProducerStatusChange(1, true, false, StatusCause.STARTING, 7);
        var tee = new RecoveryTee(List.of(throwing, recordingRecovery("health"), recordingRecovery("events")));

        tee.producerStatus(change);
        tee.producerCause(change);
        tee.eventRecoveryCompleted(2, URN.parse("od:match:1"), 3);
        tee.safetyNetReset(4, 1, 120_000);
        tee.safetyNetRequestFailed(4, 2, "refused");
        tee.lagging(4, true);

        assertThat(told)
                .containsExactly(
                        "health status 1",
                        "events status 1",
                        "health cause 1",
                        "events cause 1",
                        "health completed 2 od:match:1 3",
                        "events completed 2 od:match:1 3",
                        "health reset 4 1 120000",
                        "events reset 4 1 120000",
                        "health refused 4 2 refused",
                        "events refused 4 2 refused",
                        "health lagging 4 true",
                        "events lagging 4 true");
    }

    private RecoveryEvents recordingRecovery(String who) {
        return new RecoveryEvents() {
            @Override
            public void producerStatus(ProducerStatusChange change) {
                told.add(who + " status " + change.producerId());
            }

            @Override
            public void producerCause(ProducerStatusChange change) {
                told.add(who + " cause " + change.producerId());
            }

            @Override
            public void eventRecoveryCompleted(long producerId, URN eventId, long requestId) {
                told.add(who + " completed " + producerId + " " + eventId + " " + requestId);
            }

            @Override
            public void safetyNetReset(int session, long producerId, long ageMillis) {
                told.add(who + " reset " + session + " " + producerId + " " + ageMillis);
            }

            @Override
            public void safetyNetRequestFailed(int session, long producerId, String reason) {
                told.add(who + " refused " + session + " " + producerId + " " + reason);
            }

            @Override
            public void lagging(int session, boolean lagging) {
                told.add(who + " lagging " + session + " " + lagging);
            }
        };
    }

    private ConnectionEvents recording(String who) {
        return new ConnectionEvents() {
            @Override
            public void connecting() {
                told.add(who + " connecting");
            }

            @Override
            public void up() {
                told.add(who + " up");
            }

            @Override
            public void down(String reason) {
                told.add(who + " down " + reason);
            }

            @Override
            public void recovering(int attempt, long waitMillis, String reason) {
                told.add(who + " recovering " + attempt + " " + waitMillis + " " + reason);
            }

            @Override
            public void fatal(String reason, @Nullable Throwable cause) {
                told.add(who + " fatal " + reason);
            }
        };
    }
}
