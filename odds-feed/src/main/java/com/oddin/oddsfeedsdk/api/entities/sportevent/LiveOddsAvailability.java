package com.oddin.oddsfeedsdk.api.entities.sportevent;

import org.jspecify.annotations.Nullable;

public enum LiveOddsAvailability {
    NOT_AVAILABLE("not_available"), AVAILABLE("available");

    /**
     * Keeps {@code LiveOddsAvailability.Companion.fromApiEvent(...)} compiling: 0.0.x was Kotlin,
     * and that is how Java code reached a function of its companion object.
     */
    public static final Companion Companion = new Companion();

    private final String availability;

    LiveOddsAvailability(String availability) {
        this.availability = availability;
    }

    public String getAvailability() {
        return availability;
    }

    /** Anything but {@code not_available}, including no value, counts as available. */
    public static LiveOddsAvailability fromApiEvent(@Nullable String availability) {
        return NOT_AVAILABLE.availability.equals(availability) ? NOT_AVAILABLE : AVAILABLE;
    }

    public static final class Companion {
        private Companion() {
        }

        public LiveOddsAvailability fromApiEvent(@Nullable String availability) {
            return LiveOddsAvailability.fromApiEvent(availability);
        }
    }
}
