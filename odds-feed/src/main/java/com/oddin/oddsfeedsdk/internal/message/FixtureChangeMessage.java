package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChangeType;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFFixtureChange;
import java.util.Date;
import org.jspecify.annotations.Nullable;

/** A fixture change: what changed, the match to be read again for how. */
final class FixtureChangeMessage extends FeedEventMessage implements FixtureChange<SportEvent> {

    private final FixtureChangeType changeType;

    FixtureChangeMessage(
            SportEvent event, OFFixtureChange message, byte[] raw, Producer producer, MessageTimestamp timestamp) {
        super(event, message.getRequestId(), raw, producer, timestamp);
        this.changeType = FixtureChangeType.fromFeedType(message.getChangeType());
    }

    @Override
    public FixtureChangeType getChangeType() {
        return changeType;
    }

    /** @deprecated the feed never sends this value. */
    @Deprecated
    @SuppressWarnings("InlineMeSuggester") // a getter of the public API, not a call to inline
    @Override
    public @Nullable Date getNextLiveTime() {
        return null;
    }

    /**
     * Null: the feed sends no start time with a fixture change. 0.0.x gave 1 January 1970 (KD-4).
     *
     * @deprecated the feed never sends this value.
     */
    @Deprecated
    @Override
    // the interface keeps 0.0.x's non-null type, the value is gone; a getter of the public API, not a call to inline
    @SuppressWarnings({"NullAway", "InlineMeSuggester"})
    public Date getStartTime() {
        return null;
    }
}
