package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.List;

public interface MarketMessage<T extends SportEvent> extends EventMessage<T> {
    /**
     * The markets of the message. Each message type narrows the element type, which Java allows
     * only through the wildcard; the erased signature is the one 0.0.x had.
     */
    List<? extends Market> getMarkets();
}
