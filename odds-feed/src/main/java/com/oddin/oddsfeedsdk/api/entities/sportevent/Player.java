package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Player {
    @Nullable
    URN getId();

    @Nullable
    Map<Locale, String> getNames();

    @Nullable
    String getName(Locale locale);

    @Nullable
    Map<Locale, String> getFullNames();

    @Nullable
    String getFullName(Locale locale);

    @Nullable
    Map<Locale, String> getSportIDs();

    @Nullable
    String getSportID(Locale locale);
}
