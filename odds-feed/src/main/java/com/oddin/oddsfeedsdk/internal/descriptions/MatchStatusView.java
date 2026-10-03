package com.oddin.oddsfeedsdk.internal.descriptions;

import com.oddin.oddsfeedsdk.cache.LocalizedStaticData;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A match status description as the client holds it. A locale's description is looked up when it
 * is asked for; the one without a locale is the first of the locales it was got in that has one.
 */
final class MatchStatusView implements LocalizedStaticData {

    private final long id;
    private final String description;
    private final StatusDescriptions descriptions;

    MatchStatusView(long id, String description, StatusDescriptions descriptions) {
        this.id = id;
        this.description = description;
        this.descriptions = descriptions;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /** Null when the locale's list does not describe it. */
    @Override
    public @Nullable String getDescription(Locale locale) {
        return descriptions.description(id, locale);
    }

    @Override
    public String toString() {
        return "match status " + id + " (" + description + ")";
    }
}
