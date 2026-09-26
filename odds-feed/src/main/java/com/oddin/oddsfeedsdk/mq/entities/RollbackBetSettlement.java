package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.List;

public interface RollbackBetSettlement<T extends SportEvent> extends MarketMessage<T> {
    @Override
    List<Market> getMarkets();
}
