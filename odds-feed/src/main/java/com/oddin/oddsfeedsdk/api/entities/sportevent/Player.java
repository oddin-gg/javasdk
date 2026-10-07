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

    /**
     * Whether the player is flagged as underage, from the player's profile: {@link
     * UnderageStatus#UNKNOWN} while no profile has said. A profile that leaves the value out keeps the
     * one an earlier profile sent; one that sends -1 makes it unknown again.
     */
    @Nullable
    UnderageStatus getUnderage();
}
