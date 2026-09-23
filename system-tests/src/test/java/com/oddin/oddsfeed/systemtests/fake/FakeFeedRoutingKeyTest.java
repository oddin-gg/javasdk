package com.oddin.oddsfeed.systemtests.fake;

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
    assertThat(FakeFeed.routingKey(Fixtures.read("feed/alive/alive.xml")))
        .isEqualTo("-.-.-.alive.-.-.-.-");
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
}
