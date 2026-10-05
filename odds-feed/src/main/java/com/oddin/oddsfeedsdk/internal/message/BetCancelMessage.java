package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.MarketCancel;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetCancel;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A bet cancel, with the window of bets it cancels. */
final class BetCancelMessage extends FeedEventMessage implements BetCancel<SportEvent> {

    private final @Nullable Long startTime;
    private final @Nullable Long endTime;
    private final List<MarketCancel> markets;

    BetCancelMessage(
            SportEvent event,
            OFBetCancel message,
            byte[] raw,
            Producer producer,
            MessageTimestamp timestamp,
            List<MarketCancel> markets) {
        super(event, message.getRequestId(), raw, producer, timestamp);
        this.startTime = message.getStartTime();
        this.endTime = message.getEndTime();
        this.markets = markets;
    }

    @Override
    public @Nullable Date getStartTime() {
        return date(startTime);
    }

    @Override
    public @Nullable Date getEndTime() {
        return date(endTime);
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable String getSupercededBy() {
        return null;
    }

    @Override
    public List<MarketCancel> getMarkets() {
        return markets;
    }
}
