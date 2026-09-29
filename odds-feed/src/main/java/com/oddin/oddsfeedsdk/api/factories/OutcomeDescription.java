package com.oddin.oddsfeedsdk.api.factories;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface OutcomeDescription {
    String getId();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    Long getRefId();

    @Nullable
    String getName(Locale locale);

    @Nullable
    String getDescription(Locale locale);
}
