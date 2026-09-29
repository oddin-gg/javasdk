package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface Competition extends SportEvent {
    @Nullable
    CompetitionStatus getStatus();

    @Nullable
    List<Competitor> getCompetitors();
}
