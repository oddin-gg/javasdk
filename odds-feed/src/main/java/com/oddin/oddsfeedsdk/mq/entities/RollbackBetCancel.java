package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface RollbackBetCancel<T extends SportEvent> extends MarketMessage<T> {
    @Nullable Date getStartTime();

    @Nullable Date getEndTime();

    @Override
    List<Market> getMarkets();
}
