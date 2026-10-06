package com.oddin.oddsfeedsdk.internal.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.internal.amqp.ChannelEvents;
import com.oddin.oddsfeedsdk.internal.amqp.ConnectionEvents;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The connection's events told to each of the feed's listeners in turn, and a session's channel events bound late. */
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

        late.bind(new ChannelEvents() {
            @Override
            public void lost() {
                told.add("lost");
            }

            @Override
            public void reopened() {
                told.add("reopened");
            }
        });
        late.lost();
        late.reopened();

        assertThat(told).containsExactly("lost", "reopened");
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
