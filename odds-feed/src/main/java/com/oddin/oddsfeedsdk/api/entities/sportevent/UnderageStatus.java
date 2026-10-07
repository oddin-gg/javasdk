package com.oddin.oddsfeedsdk.api.entities.sportevent;

import org.jspecify.annotations.Nullable;

/**
 * Whether a competitor or player is flagged as underage. The API encodes it as -1 (unknown), 0 (no)
 * and 1 (yes); anything else reads as {@link #UNKNOWN}.
 */
public enum UnderageStatus {
    UNKNOWN(-1),
    NO(0),
    YES(1);

    /**
     * Keeps {@code UnderageStatus.Companion.fromValue(...)} compiling: 0.0.x was Kotlin, and that is
     * how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    private final int value;

    UnderageStatus(int value) {
        this.value = value;
    }

    /** The number the API sends for this status. */
    public int getValue() {
        return value;
    }

    /** The status for the number the API sent: {@link #UNKNOWN} for -1, for no value and for any other number. */
    public static UnderageStatus fromValue(@Nullable Integer value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value) {
            case 0 -> NO;
            case 1 -> YES;
            default -> UNKNOWN;
        };
    }

    public static final class Companion {
        private Companion() {}

        public UnderageStatus fromValue(@Nullable Integer value) {
            return UnderageStatus.fromValue(value);
        }
    }
}
