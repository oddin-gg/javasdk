package com.oddin.oddsfeed.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The stamp the fake puts on a published message, by the producers' clock. No broker needed. */
class FakeFeedStampTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String ALIVE = "<alive product=\"1\" timestamp=\"5\" subscribed=\"1\"/>";

    @Test
    void withoutSkewAMessageIsStampedWithNow() {
        assertThat(FakeFeed.stamped(ALIVE, NOW, Duration.ZERO)).contains("timestamp=\"" + NOW + "\"");
    }

    @Test
    void aProducerAheadStampsAfterNow() {
        assertThat(FakeFeed.stamped(ALIVE, NOW, Duration.ofSeconds(30)))
                .contains("timestamp=\"" + (NOW + 30_000) + "\"");
    }

    @Test
    void aProducerBehindStampsBeforeNow() {
        assertThat(FakeFeed.stamped(ALIVE, NOW, Duration.ofMinutes(-10)))
                .contains("timestamp=\"" + (NOW - 600_000) + "\"");
    }

    @Test
    void onlyTheRootTimestampMoves() {
        var message = "<odds_change product=\"2\" event_id=\"od:match:1\" timestamp=\"5\">"
                + "<odds timestamp=\"6\"/></odds_change>";
        assertThat(FakeFeed.stamped(message, NOW, Duration.ofSeconds(-30)))
                .isEqualTo("<odds_change product=\"2\" event_id=\"od:match:1\" timestamp=\"" + (NOW - 30_000)
                        + "\"><odds timestamp=\"6\"/></odds_change>");
    }

    @Test
    void aMessageWithoutATimestampIsLeftAlone() {
        var message = "<bet_stop product=\"1\" event_id=\"od:match:7\"/>";
        assertThat(FakeFeed.stamped(message, NOW, Duration.ofSeconds(30))).isEqualTo(message);
    }
}
