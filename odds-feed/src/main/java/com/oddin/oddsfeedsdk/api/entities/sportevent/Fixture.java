package com.oddin.oddsfeedsdk.api.entities.sportevent;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface Fixture {
    @Nullable Date getStartTime();

    @Nullable Map<String, String> getExtraInfo();

    @Nullable List<TvChannel> getTvChannels();
}
