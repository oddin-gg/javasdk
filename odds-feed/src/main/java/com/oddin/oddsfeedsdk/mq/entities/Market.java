package com.oddin.oddsfeedsdk.mq.entities;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Market {
    int getId();

    /**
     * Always 0, as 0.0.x read the absent attribute. Declared nullable, as 0.0.x declared it.
     *
     * @deprecated the feed never sends this value.
     */
    @Deprecated
    @Nullable
    Integer getRefId();

    Map<String, String> getSpecifiers();

    @Nullable
    String getName();

    @Nullable
    String getName(Locale locale);
}
