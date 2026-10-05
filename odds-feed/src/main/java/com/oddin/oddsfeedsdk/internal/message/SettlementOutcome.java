package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.entities.OutcomeResult;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeSettlement;
import com.oddin.oddsfeedsdk.mq.entities.VoidFactor;
import org.jspecify.annotations.Nullable;

/** An outcome of a bet settlement's market. */
final class SettlementOutcome extends FeedOutcome implements OutcomeSettlement {

    private final @Nullable VoidFactor voidFactor;
    private final OutcomeResult result;

    SettlementOutcome(String id, MarketNames names, @Nullable Double voidFactor, OutcomeResult result) {
        super(id, names);
        this.voidFactor = voidFactor(voidFactor);
        this.result = result;
    }

    /** The void factor for 0.5 and 1, null for any other, as in 0.0.x. */
    @SuppressWarnings("FloatingPointEquality") // the feed sends exactly these two values
    private static @Nullable VoidFactor voidFactor(@Nullable Double factor) {
        if (factor == null) {
            return null;
        }
        if (factor == VoidFactor.REFUND_HALF.getValue()) {
            return VoidFactor.REFUND_HALF;
        }
        if (factor == VoidFactor.REFUND_FULL.getValue()) {
            return VoidFactor.REFUND_FULL;
        }
        return null;
    }

    @Override
    public @Nullable VoidFactor getVoidFactor() {
        return voidFactor;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable Double getDeadHeatFactor() {
        return null;
    }

    @Override
    public OutcomeResult getOutcomeResult() {
        return result;
    }
}
