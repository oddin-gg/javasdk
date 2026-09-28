package com.oddin.oddsfeed.systemtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeed.systemtests.fake.FakeFeed;
import com.oddin.oddsfeed.systemtests.fake.FakeRestServer;
import com.oddin.oddsfeed.systemtests.support.KnownDifference;
import com.oddin.oddsfeed.systemtests.support.Received;
import com.oddin.oddsfeed.systemtests.support.Sdk;
import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import org.junit.jupiter.api.Test;

/** A client callback that throws: the session goes on to the next message. */
class ThrowingCallbackScenarioIT {

  private static final URN MATCH = URN.parse("od:match:198314");

  /**
   * The listener throws on the odds change; the bet stop after it still arrives. 0.0.x also
   * hands the odds change back through {@code onUnparsableMessage}, as if the feed had sent
   * something it could not read; 1.0 reports a client exception through the global listener
   * instead, and the unparsable callback is for messages that did not decode.
   */
  @Test
  void aCallbackThatThrowsDoesNotStopTheSession() throws InterruptedException {
    try (FakeRestServer rest = FakeRestServer.start();
        FakeFeed feed = FakeFeed.start();
        Sdk sdk = Sdk.against(rest, feed)) {
      Received received = sdk.open(MessageInterest.ALL, Received.throwingOn(OddsChange.class));
      feed.publishFixture("feed/odds_change/odds_change_markets_only.xml");
      feed.publishFixture("feed/bet_stop/bet_stop_all_groups.xml");

      assertThat(received.next(Message.class)).as("the first message, whose callback throws")
          .isInstanceOf(OddsChange.class);
      KnownDifference.THROWING_CALLBACK_IS_REPORTED_AS_UNPARSABLE.expect(
          () -> {
            var unparsable = received.next(Message.class);
            assertThat(unparsable).as("the message after the throw").isInstanceOf(UnparsableMessage.class);
            assertThat(((UnparsableMessage<?>) unparsable).getEvent().getId()).as("its event").isEqualTo(MATCH);
          },
          () -> {
            // nothing in between: the next message the listener gets is the bet stop
          });
      assertThat(received.next(Message.class)).as("the message after the one whose callback threw")
          .isInstanceOf(BetStop.class);
    }
  }
}
