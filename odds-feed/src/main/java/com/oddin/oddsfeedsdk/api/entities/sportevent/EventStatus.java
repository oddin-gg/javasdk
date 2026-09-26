package com.oddin.oddsfeedsdk.api.entities.sportevent;

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

    public static final class Companion {
        private Companion() {
        }

        public EventStatus fromApiEventStatus(@Nullable String status) {
            return EventStatus.fromApiEventStatus(status);
        }
    }
}
