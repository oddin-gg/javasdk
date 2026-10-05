package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.mq.entities.AdditionalProbabilities;
import com.oddin.oddsfeedsdk.mq.entities.OddsDisplayType;
import com.oddin.oddsfeedsdk.mq.entities.OutcomeOdds;
import java.math.BigDecimal;
import java.math.MathContext;
import org.jspecify.annotations.Nullable;

/** An outcome of an odds change's market. */
final class OddsOutcome extends FeedOutcome implements OutcomeOdds {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MINUS_HUNDRED = BigDecimal.valueOf(-100);

    private final @Nullable Double odds;
    private final @Nullable Double probability;
    private final boolean active;

    /** @param active false only when the feed says the outcome is inactive, as in 0.0.x */
    OddsOutcome(String id, MarketNames names, @Nullable Double odds, @Nullable Double probability, boolean active) {
        super(id, names);
        this.odds = odds;
        this.probability = probability;
        this.active = active;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public @Nullable Double getProbability() {
        return probability;
    }

    /** Never: the feed no longer says which team an outcome is for. */
    @Override
    public boolean isPlayerOutcome() {
        return false;
    }

    /**
     * The odds as the feed sent them, decimal, or in American display: {@code (odds - 1) * 100} from
     * 2 up, {@code -100 / (odds - 1)} below, and null for odds of 1 or less or not finite, which have
     * none - as the Go SDK gives them. 0.0.x gave {@code odds - 100} from 2 up (KD-24).
     */
    @Override
    public @Nullable Double getOdds(OddsDisplayType oddsDisplayType) {
        return switch (oddsDisplayType) {
            case DECIMAL -> odds;
            case AMERICAN -> american(odds);
        };
    }

    static @Nullable Double american(@Nullable Double odds) {
        if (odds == null || !Double.isFinite(odds) || odds <= 1.0) {
            return null;
        }
        BigDecimal over = BigDecimal.valueOf(odds).subtract(BigDecimal.ONE);
        return odds >= 2.0
                ? over.multiply(HUNDRED).doubleValue()
                : MINUS_HUNDRED.divide(over, MathContext.DECIMAL128).doubleValue();
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable AdditionalProbabilities getAdditionalProbabilities() {
        return null;
    }
}
