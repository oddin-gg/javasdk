package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface BetStop<T extends SportEvent> extends EventMessage<T> {
    @Nullable List<String> getGroups();

    MarketStatus getMarketStatus();
}
