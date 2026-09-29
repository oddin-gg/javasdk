package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface Sport extends SportSummary {
    @Nullable
    List<Tournament> getTournaments();
}
