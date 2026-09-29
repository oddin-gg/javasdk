package com.oddin.oddsfeedsdk.subscribe;

import com.oddin.oddsfeedsdk.mq.MessageInterest;
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo;
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import java.net.URI;

/** Everything as it arrived: each feed message as its XML class, each API response as its XML class. */
public interface OddsFeedExtListener {
    void onRawFeedMessageReceived(
            UnparsedMessage message,
            MessageInterest messageInterest,
            RoutingKeyInfo routingKey,
            MessageTimestamp timestamp);

    /** {@code data} is the response's XML class, one of {@code com.oddin.oddsfeedsdk.schema.rest.v1}. */
    void onRawApiDataReceived(URI uri, Object data);
}
