package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.cache.StaticData;
import com.oddin.oddsfeedsdk.mq.entities.MarketWithOdds;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * An odds change. Its markets are built when first asked for and kept; a market whose status this
 * SDK does not know, or that has none, fails that with a {@link NullPointerException}, as in 0.0.x,
 * and the next call tries again.
 */
final class OddsChangeMessage extends FeedEventMessage implements OddsChange<SportEvent> {

    private final Supplier<List<MarketWithOdds>> build;
    private volatile @Nullable List<MarketWithOdds> markets;

    OddsChangeMessage(
            SportEvent event,
            OFOddsChange message,
            byte[] raw,
            Producer producer,
            MessageTimestamp timestamp,
            Supplier<List<MarketWithOdds>> markets) {
        super(event, message.getRequestId(), raw, producer, timestamp);
        this.build = markets;
    }

    @Override
    public List<MarketWithOdds> getMarkets() {
        List<MarketWithOdds> built = markets;
        if (built == null) {
            built = build.get();
            markets = built;
        }
        return built;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable StaticData getBetStopReasonData() {
        return null;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable String getBetStopReason() {
        return null;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable StaticData getBettingStatusData() {
        return null;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable String getBettingStatus() {
        return null;
    }
}
