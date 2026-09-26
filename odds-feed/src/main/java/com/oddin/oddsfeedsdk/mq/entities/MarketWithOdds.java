package com.oddin.oddsfeedsdk.mq.entities;

import java.util.List;

public interface MarketWithOdds extends Market {
    MarketStatus getStatus();

    List<OutcomeOdds> getOutcomeOdds();

    boolean isFavourite();
}
