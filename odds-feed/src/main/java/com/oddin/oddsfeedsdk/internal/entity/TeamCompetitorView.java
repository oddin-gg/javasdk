package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.api.entities.sportevent.TeamCompetitor;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** A competitor of a match, with the qualifier the match gives it, such as {@code home}. */
final class TeamCompetitorView extends CompetitorView implements TeamCompetitor {

    private final @Nullable String qualifier;

    TeamCompetitorView(Entities entities, URN id, @Nullable String qualifier, List<Locale> locales) {
        super(entities, id, locales);
        this.qualifier = qualifier;
    }

    @Override
    public @Nullable String getQualifier() {
        return qualifier;
    }
}
