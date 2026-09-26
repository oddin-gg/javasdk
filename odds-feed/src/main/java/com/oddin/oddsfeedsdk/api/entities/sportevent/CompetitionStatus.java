package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface CompetitionStatus {
    @Nullable URN getWinnerId();

    @Nullable EventStatus getStatus();

    @Nullable Map<String, @Nullable Object> getProperties();
}
