package com.oddin.oddsfeed.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The routing keys the fake derives, in the shape the feed uses. No broker needed. */
class FakeFeedRoutingKeyTest {

    @Test
    void aLiveEventMessageCarriesItsTypeAndEvent() {
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/odds_change/odds_change_markets_only.xml")))
                .isEqualTo("hi.-.live.odds_change.-.od:match.198314.-");
    }

    @Test
    void theProducerDecidesPrematchOrLive() {
        assertThat(FakeFeed.routingKey("<bet_stop product=\"1\" event_id=\"od:match:7\" timestamp=\"1\"/>"))
                .isEqualTo("hi.pre.-.bet_stop.-.od:match.7.-");
        assertThat(FakeFeed.routingKey("<bet_stop product=\"3\" event_id=\"od:match:7\" timestamp=\"1\"/>"))
                .isEqualTo("hi.-.-.bet_stop.-.od:match.7.-");
    }

    @Test
    void systemMessagesCarryOnlyTheirType() {
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/alive/alive.xml"))).isEqualTo("-.-.-.alive.-.-.-.-");
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/snapshot_complete/snapshot_complete.xml")))
                .isEqualTo("-.-.-.snapshot_complete.-.-.-.-");
    }

    @Test
    void anXmlDeclarationIsSkipped() {
        assertThat(FakeFeed.routingKey("<?xml version=\"1.0\"?>\n<odds_change product=\"2\" event_id=\"od:match:1\"/>"))
                .isEqualTo("hi.-.live.odds_change.-.od:match.1.-");
    }

    @Test
    void somethingElseIsRefused() {
        assertThatThrownBy(() -> FakeFeed.routingKey("not xml")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNodeReplacesTheAnyNodeOfTheKey() {
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/odds_change/odds_change_markets_only.xml"), 7))
                .isEqualTo("hi.-.live.odds_change.-.od:match.198314.7");
        assertThat(FakeFeed.routingKey("<bet_stop product=\"1\" event_id=\"od:match:7\" timestamp=\"1\"/>", 0))
                .isEqualTo("hi.pre.-.bet_stop.-.od:match.7.0");
    }

    @Test
    void aSnapshotCompleteForANodeCarriesItAtTheEnd() {
        // the key the SDK binds for its node: -.-.-.snapshot_complete.-.-.-.<node>
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/snapshot_complete/snapshot_complete.xml"), 8))
                .isEqualTo("-.-.-.snapshot_complete.-.-.-.8");
    }

    @Test
    void anAliveForANodeKeepsTheShapeTheAliveBindingTakes() {
        assertThat(FakeFeed.routingKey(Fixtures.read("feed/alive/alive.xml"), 8))
                .isEqualTo("-.-.-.alive.-.-.-.8");
    }

    @Test
    void noNodeIsTheKeyForAnyNode() {
        var message = Fixtures.read("feed/odds_change/odds_change_markets_only.xml");
        assertThat(FakeFeed.routingKey(message)).endsWith(".-");
        assertThat(FakeFeed.routingKey(message, 3))
                .isEqualTo(FakeFeed.routingKey(message).replaceAll("-$", "3"));
    }
}
