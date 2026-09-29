package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Match extends Competition {
    @Override
    @Nullable
    MatchStatus getStatus();

    @Nullable
    Tournament getTournament();

    @Nullable
    TeamCompetitor getHomeCompetitor();

    @Nullable
    TeamCompetitor getAwayCompetitor();

    @Nullable
    Fixture getFixture();

    @Nullable
    SportFormat getSportFormat();

    @Nullable
    Map<String, String> getExtraInfo();
}
