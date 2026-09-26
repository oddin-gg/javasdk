package com.oddin.oddsfeedsdk.api.factories;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface MarketVoidReason {
    int getId();

    @Nullable String getName();

    @Nullable String getDescription();

    @Nullable String getTemplate();

    @Nullable List<String> getParams();
}
