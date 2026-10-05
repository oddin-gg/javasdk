package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeOdds;
import java.util.List;
import java.util.Map;

/** A market of an odds change. */
final class OddsMarket extends FeedMarket implements MarketWithOdds {

    private final MarketStatus status;
    private final List<OutcomeOdds> outcomes;
    private final boolean favourite;

    OddsMarket(
            int id,
            Map<String, String> specifiers,
            MarketNames names,
            MarketStatus status,
            List<OutcomeOdds> outcomes,
            boolean favourite) {
        super(id, specifiers, names);
        this.status = status;
        this.outcomes = outcomes;
        this.favourite = favourite;
    }

    @Override
    public MarketStatus getStatus() {
        return status;
    }

    @Override
    public List<OutcomeOdds> getOutcomeOdds() {
        return outcomes;
    }

    @Override
    public boolean isFavourite() {
        return favourite;
    }
}
