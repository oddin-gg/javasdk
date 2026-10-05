package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFChangeType;
import org.jspecify.annotations.Nullable;

public enum FixtureChangeType {
    NEW,
    TIME_UPDATE,
    CANCELLED,
    OTHER_CHANGE,
    COVERAGE,
    STREAM_URL;

    /**
     * Keeps {@code FixtureChangeType.Companion.fromFeedType(...)} compiling: 0.0.x was Kotlin, and
     * that is how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    /**
     * The type for the change the feed sent; a change the feed sends that this SDK does not know,
     * or none, is {@link #OTHER_CHANGE}, as in 0.0.x.
     */
    @SuppressWarnings("deprecation") // FORMAT is no longer sent, and still maps as in 0.0.x
    public static FixtureChangeType fromFeedType(@Nullable OFChangeType type) {
        if (type == null) {
            return OTHER_CHANGE;
        }
        return switch (type) {
            case NEW -> NEW;
            case CANCELLED -> CANCELLED;
            case DATETIME -> TIME_UPDATE;
            case COVERAGE -> COVERAGE;
            case STREAM_URL -> STREAM_URL;
            case FORMAT, UNKNOWN -> OTHER_CHANGE;
        };
    }

    public static final class Companion {
        private Companion() {}

        public FixtureChangeType fromFeedType(@Nullable OFChangeType type) {
            return FixtureChangeType.fromFeedType(type);
        }
    }
}
