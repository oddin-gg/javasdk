package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface Tournament extends LongTermEvent {
    @Nullable List<Competitor> getCompetitors();

    @Nullable Date getStartDate();

    @Nullable Date getEndDate();

    @Nullable Integer getRiskTier();

    @Nullable String getAbbreviation(Locale locale);
}
