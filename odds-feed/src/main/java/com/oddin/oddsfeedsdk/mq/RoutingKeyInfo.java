package com.oddin.oddsfeedsdk.mq;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The routing key a feed message arrived with, and the ids read from it.
 */
public final class RoutingKeyInfo {
    private final String fullRoutingKey;
    private final @Nullable URN sportId;
    private final @Nullable URN eventId;
    private final boolean isSystemRoutingKey;

    public RoutingKeyInfo(
            String fullRoutingKey,
            @Nullable URN sportId,
            @Nullable URN eventId,
            boolean isSystemRoutingKey) {
        this.fullRoutingKey = fullRoutingKey;
        this.sportId = sportId;
        this.eventId = eventId;
        this.isSystemRoutingKey = isSystemRoutingKey;
    }

    public String getFullRoutingKey() {
        return fullRoutingKey;
    }

    public @Nullable URN getSportId() {
        return sportId;
    }

    public @Nullable URN getEventId() {
        return eventId;
    }

    public boolean isSystemRoutingKey() {
        return isSystemRoutingKey;
    }

    /** True when the routing key carried both a sport id and an event id. */
    public boolean hasValidURN() {
        return sportId != null && eventId != null;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoutingKeyInfo that)) {
            return false;
        }
        return Objects.equals(fullRoutingKey, that.fullRoutingKey)
                && Objects.equals(sportId, that.sportId)
                && Objects.equals(eventId, that.eventId)
                && isSystemRoutingKey == that.isSystemRoutingKey;
    }

    @Override
    public int hashCode() {
        int result = fullRoutingKey.hashCode();
        result = 31 * result + Objects.hashCode(sportId);
        result = 31 * result + Objects.hashCode(eventId);
        result = 31 * result + Boolean.hashCode(isSystemRoutingKey);
        return result;
    }

    @Override
    public String toString() {
        return "RoutingKeyInfo(" + "fullRoutingKey=" + fullRoutingKey + ", "
                + "sportId=" + sportId + ", "
                + "eventId=" + eventId + ", "
                + "isSystemRoutingKey=" + isSystemRoutingKey + ")";
    }
}
