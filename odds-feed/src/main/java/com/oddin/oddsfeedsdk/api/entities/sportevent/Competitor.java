package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Competitor {
    @Nullable
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
    Map<Locale, String> getCountries();

    @Nullable
    Map<Locale, String> getAbbreviations();

    @Nullable
    Boolean getVirtual();

    @Nullable
    String getCountryCode();

    @Nullable
    Integer getUnderage();

    @Nullable
    String getIconPath();

    @Nullable
    String getCountry(Locale locale);

    @Nullable
    String getAbbreviation(Locale locale);

    @Nullable
    List<@Nullable Player> getPlayers();
}
