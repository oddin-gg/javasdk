package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface BetCancel<T extends SportEvent> extends MarketMessage<T> {
    @Nullable
    Date getStartTime();

    @Nullable
    Date getEndTime();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    String getSupercededBy();

    @Override
    List<MarketCancel> getMarkets();
}
