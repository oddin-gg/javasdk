package com.oddin.oddsfeedsdk.internal.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import org.junit.jupiter.api.Test;

/** The ids a routing key names, read as 0.0.x read them. */
class RoutesTest {

    @Test
    void aMessagesKeyNamesItsSportAndEvent() {
        RoutingKeyInfo key = Routes.parse("hi.pre.-.odds_change.5.od:match.198314.-");
        assertThat(key.getSportId()).isEqualTo(URN.parse("od:sport:5"));
        assertThat(key.getEventId()).isEqualTo(URN.parse("od:match:198314"));
        assertThat(key.isSystemRoutingKey()).isFalse();
        assertThat(key.getFullRoutingKey()).isEqualTo("hi.pre.-.odds_change.5.od:match.198314.-");
    }

    @Test
    void aKeyWithAnEventAndNoSportOrNoNodeNamesWhatItHas() {
        RoutingKeyInfo key = Routes.parse("hi.-.live.bet_stop.-.od:tournament.7");
        assertThat(key.getSportId()).isNull();
        assertThat(key.getEventId()).isEqualTo(URN.parse("od:tournament:7"));
        assertThat(key.isSystemRoutingKey()).isFalse();
        assertThat(Routes.parse("hi.-.live.bet_stop.-.od:match.7.42").getEventId())
                .isEqualTo(URN.parse("od:match:7"));
    }

    @Test
    void aSystemKeyOrOneOfAnotherShapeNamesNothing() {
        assertThat(Routes.parse("-.-.-.alive.-.-.-.-"))
                .isEqualTo(new RoutingKeyInfo("-.-.-.alive.-.-.-.-", null, null, true));
        assertThat(Routes.parse("-.-.-.snapshot_complete.-.-.-.3").isSystemRoutingKey())
                .isTrue();
        assertThat(Routes.parse("not a routing key"))
                .isEqualTo(new RoutingKeyInfo("not a routing key", null, null, true));
    }
}
