package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlementCertainty;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithSettlement;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetSettlement;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A bet settlement. Its markets are built when first asked for and kept; an outcome whose result
 * this SDK does not know, or that has none, fails that with a {@link NullPointerException}, as in
 * 0.0.x.
 */
final class BetSettlementMessage extends FeedEventMessage implements BetSettlement<SportEvent> {

    private final Supplier<List<MarketWithSettlement>> build;
    private volatile @Nullable List<MarketWithSettlement> markets;

    BetSettlementMessage(
            SportEvent event,
            OFBetSettlement message,
            byte[] raw,
            Producer producer,
            MessageTimestamp timestamp,
            Supplier<List<MarketWithSettlement>> markets) {
        super(event, message.getRequestId(), raw, producer, timestamp);
        this.build = markets;
    }

    /**
     * {@link BetSettlementCertainty#UNKNOWN}: the feed no longer sends a certainty.
     *
     * @deprecated the feed never sends this value.
     */
    @Deprecated
    @Override
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    public BetSettlementCertainty getCertainty() {
        return BetSettlementCertainty.UNKNOWN;
    }

    @Override
    public List<MarketWithSettlement> getMarkets() {
        List<MarketWithSettlement> built = markets;
        if (built == null) {
            built = build.get();
            markets = built;
        }
        return built;
    }
}
