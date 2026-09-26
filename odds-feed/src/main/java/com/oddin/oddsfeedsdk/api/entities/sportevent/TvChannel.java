package com.oddin.oddsfeedsdk.api.entities.sportevent;

import org.jspecify.annotations.Nullable;

public interface TvChannel {
    String getName();

    String getStreamUrl();

    @Nullable String getLanguage();
}
