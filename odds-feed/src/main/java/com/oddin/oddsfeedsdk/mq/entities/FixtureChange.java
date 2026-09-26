package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import java.util.Date;
import org.jspecify.annotations.Nullable;

public interface FixtureChange<T extends SportEvent> extends EventMessage<T> {
    FixtureChangeType getChangeType();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable Date getNextLiveTime();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    Date getStartTime();
}
