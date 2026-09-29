package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFEventStatus;
import org.jspecify.annotations.Nullable;

public enum EventStatus {
    NotStarted("not_started", 0),
    Live("live", 1),
    Suspended("suspended", 2),
    Ended("ended", 3),
    Finished("closed", 4),
    Cancelled("cancelled", 5),
    Abandoned("abandoned", 6),
    Delayed("delayed", 7),
    Unknown("unknown", 8),
    Postponed("postponed", 9),
    Interrupted("interrupted", 10);

    /**
     * Keeps {@code EventStatus.Companion.fromApiEventStatus(...)} compiling: 0.0.x was Kotlin,
     * and that is how Java code reached a function of its companion object.
     */
    public static final Companion Companion = new Companion();

    private final String apiName;
    private final int apiId;

    EventStatus(String apiName, int apiId) {
        this.apiName = apiName;
        this.apiId = apiId;
    }

    public String getApiName() {
        return apiName;
    }

    public int getApiId() {
        return apiId;
    }

    /** The status with this REST name, or {@link #Unknown}. */
    public static EventStatus fromApiEventStatus(@Nullable String status) {
        for (EventStatus candidate : values()) {
            if (candidate.apiName.equals(status)) {
                return candidate;
            }
        }
        return Unknown;
    }

    /**
     * The status for the number the feed sends. The feed numbers differ from {@link #getApiId()}
     * (the feed's 9 is {@link #Abandoned}), so this maps by name; {@link OFEventStatus#UNKNOWN} is
     * {@link #Unknown}.
     */
    @SuppressWarnings("deprecation") // statuses the schema no longer lists still map, as in 0.0.x
    public static EventStatus fromFeedEventStatus(OFEventStatus status) {
        // no default: a feed status added later fails the build until it is mapped
        return switch (status) {
            case NOT_STARTED -> NotStarted;
            case LIVE -> Live;
            case SUSPENDED -> Suspended;
            case ENDED -> Ended;
            case FINALIZED -> Finished;
            case CANCELLED -> Cancelled;
            case DELAYED -> Delayed;
            case INTERRUPTED -> Interrupted;
            case POSTPONED -> Postponed;
            case ABANDONED -> Abandoned;
            case UNKNOWN -> Unknown;
        };
    }

    public static final class Companion {
        private Companion() {
        }

        public EventStatus fromApiEventStatus(@Nullable String status) {
            return EventStatus.fromApiEventStatus(status);
        }

        public EventStatus fromFeedEventStatus(OFEventStatus status) {
            return EventStatus.fromFeedEventStatus(status);
        }
    }
}
