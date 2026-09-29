package com.oddin.oddsfeedsdk.mq.entities;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public interface Outcome {
    String getId();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable
    Long getRefId();

    @Nullable
    String getName();

    @Nullable
    String getName(Locale locale);
}
