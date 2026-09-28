package com.oddin.oddsfeedsdk.schema.feed.v1;

/**
 * A sport event's status as the feed sends it.
 *
 * <p>The XML carries the number; the generated classes keep it next to this enum, so a value this
 * SDK does not know yet reads as {@link #UNKNOWN} here and stays readable as the number.
 */
public enum OFEventStatus {
    NOT_STARTED(0),
    LIVE(1),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    SUSPENDED(2),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    ENDED(3),
    FINALIZED(4),
    CANCELLED(5),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    DELAYED(6),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    INTERRUPTED(7),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    POSTPONED(8),
    /** @deprecated the feed never sends this value. */
    @Deprecated
    ABANDONED(9),
    /** A value this SDK does not know. It has no number of its own: {@link #value()} fails for it. */
    UNKNOWN(Integer.MIN_VALUE);

    private final int value;

    OFEventStatus(int value) {
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
    public static OFEventStatus fromValue(int value) {
        for (OFEventStatus candidate : values()) {
            if (candidate != UNKNOWN && candidate.value == value) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(String.valueOf(value));
    }

    /** The constant for the number as decoded, {@link #UNKNOWN} for one without a constant, null for none. */
    static OFEventStatus fromRaw(Integer raw) {
        if (raw == null) {
            return null;
        }
        for (OFEventStatus candidate : values()) {
            if (candidate != UNKNOWN && candidate.value == raw) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /** The number to encode, null for none; {@link #UNKNOWN} cannot be encoded. */
    static Integer toRaw(OFEventStatus value) {
        return value == null ? null : value.value();
    }
}
