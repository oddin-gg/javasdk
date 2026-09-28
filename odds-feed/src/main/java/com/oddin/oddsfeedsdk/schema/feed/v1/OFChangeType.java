package com.oddin.oddsfeedsdk.schema.feed.v1;

/**
 * What a fixture change changed.
 *
 * <p>The XML carries the number; the generated classes keep it next to this enum, so a value this
 * SDK does not know yet reads as {@link #UNKNOWN} here and stays readable as the number.
 */
public enum OFChangeType {
    NEW(1),
    DATETIME(2),
    CANCELLED(3),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    FORMAT(4),
    COVERAGE(5),
    STREAM_URL(106),
    /** A value this SDK does not know. It has no number of its own: {@link #value()} fails for it. */
    UNKNOWN(Integer.MIN_VALUE);

    private final int value;

    OFChangeType(int value) {
        this.value = value;
    }

    /** The number the feed sends for this value. */
    public int value() {
        if (this == UNKNOWN) {
            throw new IllegalStateException("UNKNOWN has no feed value");
        }
        return value;
    }

    /**
     * The constant for a number the feed sends.
     *
     * @throws IllegalArgumentException for a number that has no constant, as in 0.0.x
     */
    public static OFChangeType fromValue(int value) {
        for (OFChangeType candidate : values()) {
            if (candidate != UNKNOWN && candidate.value == value) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(String.valueOf(value));
    }

    /** The constant for the number as decoded, {@link #UNKNOWN} for one without a constant, null for none. */
    static OFChangeType fromRaw(Integer raw) {
        if (raw == null) {
            return null;
        }
        for (OFChangeType candidate : values()) {
            if (candidate != UNKNOWN && candidate.value == raw) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /** The number to encode, null for none; {@link #UNKNOWN} cannot be encoded. */
    static Integer toRaw(OFChangeType value) {
        return value == null ? null : value.value();
    }
}
