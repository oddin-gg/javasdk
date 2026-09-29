package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.cache.StaticData;
import org.jspecify.annotations.Nullable;

public interface MarketCancel extends Market {
    /** @deprecated use {@link #getVoidReasonId()} and {@link #getVoidReasonParams()}. */
    @Deprecated
    @Nullable
    StaticData getVoidReasonValue();

    /** @deprecated use {@link #getVoidReasonId()} and {@link #getVoidReasonParams()}. */
    @Deprecated
    @Nullable
    String getVoidReason();

    @Nullable
    Integer getVoidReasonId();

    @Nullable
    String getVoidReasonParams();
}
