package com.oddin.oddsfeedsdk.internal.message;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.MarketStatus;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFBetStop;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A bet stop. */
final class BetStopMessage extends FeedEventMessage implements BetStop<SportEvent> {

    private final OFBetStop message;

    BetStopMessage(SportEvent event, OFBetStop message, byte[] raw, Producer producer, MessageTimestamp timestamp) {
        super(event, message.getRequestId(), raw, producer, timestamp);
        this.message = message;
    }

    /**
     * The market groups, split on the pipe the feed separates them with: {@code winner|handicap} is
     * {@code winner} and {@code handicap}. 0.0.x split on the two characters {@code \|} and gave one
     * group (KD-6). A new list on each call, which the client may change.
     */
    @Override
    public @Nullable List<String> getGroups() {
        String groups = message.getGroups();
        // -1 keeps an empty last group, as Kotlin's split, which 0.0.x used, keeps it
        return groups == null ? null : new ArrayList<>(Arrays.asList(groups.split("\\|", -1)));
    }

    /**
     * @throws NullPointerException when the feed sent a status this SDK does not know, or none, as
     *     in 0.0.x
     */
    @Override
    public MarketStatus getMarketStatus() {
        return MarketStatus.fromFeedValue(message.getMarketStatus());
    }
}
