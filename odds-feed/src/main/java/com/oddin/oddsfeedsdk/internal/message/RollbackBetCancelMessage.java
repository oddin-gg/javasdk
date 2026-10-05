package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.Market;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFRollbackBetCancel;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A rollback of a bet cancel, with the window it restores. It has no request id, as in 0.0.x. */
final class RollbackBetCancelMessage extends FeedEventMessage implements RollbackBetCancel<SportEvent> {

    private final @Nullable Long startTime;
    private final @Nullable Long endTime;
    private final List<Market> markets;

    RollbackBetCancelMessage(
            SportEvent event,
            OFRollbackBetCancel message,
            byte[] raw,
            Producer producer,
            MessageTimestamp timestamp,
            List<Market> markets) {
        super(event, null, raw, producer, timestamp);
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

    @Override
    public List<Market> getMarkets() {
        return markets;
    }
}
