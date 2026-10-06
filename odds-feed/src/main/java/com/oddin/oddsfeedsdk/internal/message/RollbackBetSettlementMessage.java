package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import java.util.ArrayList;
import java.util.List;

/** A rollback of a bet settlement. It has no request id, as in 0.0.x. */
final class RollbackBetSettlementMessage extends FeedEventMessage implements RollbackBetSettlement<SportEvent> {

    private final List<Market> markets;

    RollbackBetSettlementMessage(
            SportEvent event, byte[] raw, Producer producer, MessageTimestamp timestamp, List<Market> markets) {
        super(event, null, raw, producer, timestamp);
        this.markets = markets;
    }

    /** A new list on each call, which the client may change: the message keeps its own. */
    @Override
    public List<Market> getMarkets() {
        return new ArrayList<>(markets);
    }
}
