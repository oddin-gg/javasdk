package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.schema.feed.v1.OFMarketStatus;
import org.jspecify.annotations.Nullable;

public enum MarketStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED,
    SETTLED,
    CANCELLED,
    HANDED_OVER;

    /**
     * Keeps {@code MarketStatus.Companion.fromFeedValue(...)} compiling: 0.0.x was Kotlin, and that
     * is how Java code reached a function of its companion object.
     */
    @SuppressWarnings("VariableNameSameAsType") // the name is the compatibility
    public static final Companion Companion = new Companion();

    /**
     * The status for the one the feed sent. A status the feed sends that this SDK does not know, or
     * none at all, has no constant here, and is a {@link NullPointerException}, as in 0.0.x: so the
     * getter that meets it throws, and the message that carries it is still delivered.
     *
     * @throws NullPointerException for {@link OFMarketStatus#UNKNOWN} and for null
     */
    public static MarketStatus fromFeedValue(OFMarketStatus status) {
        return switch (known(status)) {
            case ACTIVE -> ACTIVE;
            case DEACTIVATED -> DEACTIVATED;
            case SUSPENDED -> SUSPENDED;
            case HANDED_OVER -> HANDED_OVER;
            case SETTLED -> SETTLED;
            case CANCELLED -> CANCELLED;
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN was refused above");
        };
    }

    private static OFMarketStatus known(@Nullable OFMarketStatus status) {
        if (status == null || status == OFMarketStatus.UNKNOWN) {
            throw new NullPointerException("the feed sent a market status this SDK does not know, or none");
        }
        return status;
    }

    public static final class Companion {
        private Companion() {}

        public MarketStatus fromFeedValue(OFMarketStatus status) {
            return MarketStatus.fromFeedValue(status);
        }
    }
}
