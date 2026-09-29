package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface SportSummary {
    URN getId();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    URN getRefId();

    @Nullable
    Map<Locale, String> getNames();

    @Nullable
    String getName(Locale locale);

    @Nullable
    String getAbbreviation(Locale locale);

    @Nullable
    String getIconPath(Locale locale);
}
