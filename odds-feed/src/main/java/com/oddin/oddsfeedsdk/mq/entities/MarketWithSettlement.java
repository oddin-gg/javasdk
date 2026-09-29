package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.cache.StaticData;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface MarketWithSettlement extends Market {
    List<OutcomeSettlement> getOutcomeSettlements();

    @Nullable
    StaticData getVoidReasonValue();

    @Nullable
    String getVoidReason();
}
