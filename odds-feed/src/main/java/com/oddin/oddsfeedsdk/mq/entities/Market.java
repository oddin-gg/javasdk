package com.oddin.oddsfeedsdk.mq.entities;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Market {
    int getId();

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @Nullable Integer getRefId();

    Map<String, String> getSpecifiers();

    @Nullable String getName();

    @Nullable String getName(Locale locale);
}
