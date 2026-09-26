package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface SportEvent {
    @Nullable URN getId();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable URN getRefId();

    @Nullable String getName(Locale locale);

    @Nullable URN getSportId();

    @Nullable Date getScheduledTime();

    @Nullable Date getScheduledEndTime();

    @Nullable LiveOddsAvailability getLiveOddsAvailability();
}
