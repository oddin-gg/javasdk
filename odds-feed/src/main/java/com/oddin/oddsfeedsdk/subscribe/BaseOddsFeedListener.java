package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;

/** The messages of one session, one callback per message type. */
public interface BaseOddsFeedListener<T extends SportEvent> {
    void onOddsChange(OddsFeedSession session, OddsChange<T> message);

    void onBetStop(OddsFeedSession session, BetStop<T> message);

    void onBetSettlement(OddsFeedSession session, BetSettlement<T> message);

    void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<T> message);

    void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<T> message);

    void onBetCancel(OddsFeedSession session, BetCancel<T> message);

    void onFixtureChange(OddsFeedSession session, FixtureChange<T> message);

    /**
     * A message the SDK could not decode, or one over the maximum message size, which is not
     * decoded; does nothing unless overridden, as in 0.0.x. A message that decoded but that the SDK
     * failed to write to its caches or to build is reported to the global listener's {@code
     * onCallbackFailure} instead, where 0.0.x called this.
     */
    default void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<T> message) {}
}
