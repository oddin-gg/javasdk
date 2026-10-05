package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.entities.Outcome;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** An outcome of a market of a message: its id as the feed sent it, its name from the catalog. */
class FeedOutcome implements Outcome {

    private final String id;
    private final MarketNames names;

    FeedOutcome(String id, MarketNames names) {
        this.id = id;
        this.names = names;
    }

    @Override
    public String getId() {
        return id;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable Long getRefId() {
        return null;
    }

    /** The name in the default locale. */
    @Override
    public @Nullable String getName() {
        return names.outcome(id, names.defaultLocale());
    }

    @Override
    public @Nullable String getName(Locale locale) {
        return names.outcome(id, locale);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + id + ")";
    }
}
