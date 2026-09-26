package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.List;

public interface BetSettlement<T extends SportEvent> extends MarketMessage<T> {
    /** @deprecated the feed never sends this value. */
    @Deprecated
    BetSettlementCertainty getCertainty();

    @Override
    List<MarketWithSettlement> getMarkets();
}
