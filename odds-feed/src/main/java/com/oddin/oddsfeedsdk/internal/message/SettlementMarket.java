package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.cache.StaticData;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithSettlement;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeSettlement;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A market of a bet settlement. Its void reason is null, as in 0.0.x: a settled market carries none. */
final class SettlementMarket extends FeedMarket implements MarketWithSettlement {

    private final List<OutcomeSettlement> outcomes;

    SettlementMarket(int id, Map<String, String> specifiers, MarketNames names, List<OutcomeSettlement> outcomes) {
        super(id, specifiers, names);
        this.outcomes = outcomes;
    }

    @Override
    public List<OutcomeSettlement> getOutcomeSettlements() {
        return outcomes;
    }

    @Override
    public @Nullable StaticData getVoidReasonValue() {
        return null;
    }

    @Override
    public @Nullable String getVoidReason() {
        return null;
    }
}
