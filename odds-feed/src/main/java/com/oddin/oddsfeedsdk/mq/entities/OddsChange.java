package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.cache.StaticData;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface OddsChange<T extends SportEvent> extends MarketMessage<T> {
    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    StaticData getBetStopReasonData();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    String getBetStopReason();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    StaticData getBettingStatusData();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    String getBettingStatus();

    @Override
    List<MarketWithOdds> getMarkets();
}
